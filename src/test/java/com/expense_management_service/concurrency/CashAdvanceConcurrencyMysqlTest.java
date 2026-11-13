package com.expense_management_service.concurrency;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.impl.CashAdvanceAdjustmentServiceImpl;
import com.expense_management_service.service.impl.CashAdvanceBalanceCalculator;
import com.expense_management_service.service.impl.CashAdvanceBalanceLedger;
import com.expense_management_service.service.impl.CashAdvanceBalanceRules;
import com.expense_management_service.service.impl.CashAdvanceBudgetMovements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Real-database concurrency tests on MySQL 8 (never the shared dev database).
 *
 * <p>Runs only when CA_IT_MYSQL_URL is set, pointing at a DISPOSABLE database whose name contains {@code ca_it}
 * (the test refuses any other name). Credentials come from CA_IT_MYSQL_USER and CA_IT_MYSQL_PASSWORD. Each test
 * creates its own fixtures under a random employee id and deletes them afterwards.
 *
 * <p>The service and the repositories are the real ones. Only the audit publisher, budget movements, the user lookup
 * and the status ledger are mocks. Each thread runs its own transaction, so the database locks are what is tested.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${CA_IT_MYSQL_URL:}",
        "spring.datasource.username=${CA_IT_MYSQL_USER:}",
        "spring.datasource.password=${CA_IT_MYSQL_PASSWORD:}"
})
@Import({CashAdvanceAdjustmentServiceImpl.class, CashAdvanceAdjustmentMapper.class, CashAdvanceBalanceCalculator.class})
@EnabledIfEnvironmentVariable(named = "CA_IT_MYSQL_URL", matches = ".*ca_it.*")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CashAdvanceConcurrencyMysqlTest {

    private static final String EMP = "CONC-EMP";

    @MockitoBean CashAdvanceBudgetMovements budgetMovements;
    @MockitoBean CurrentUserService currentUserService;
    @MockitoBean ApprovalEventPublisher approvalEventPublisher;
    @MockitoBean CashAdvanceBalanceLedger balanceLedger;

    @Autowired CashAdvanceAdjustmentServiceImpl service;
    @Autowired JdbcTemplate jdbc;

    private final List<ExecutorService> pools = new ArrayList<>();
    private byte[] currencyId;
    private byte[] costCenterId;

    @BeforeEach
    void setUp() {
        when(currentUserService.getEmployeeId()).thenReturn("FIN001");
        when(balanceLedger.statusFor(any(), any(), anyInt()))
                .thenAnswer(inv -> CashAdvanceBalanceRules.statusAfterBalance(inv.<BigDecimal>getArgument(1), inv.<Integer>getArgument(2)));
        String url = System.getenv("CA_IT_MYSQL_URL");
        assertTrue(url.contains("ca_it"), "Refusing to run: the test database name must contain ca_it");

        currencyId = bytes(UUID.randomUUID());
        costCenterId = bytes(UUID.randomUUID());
        jdbc.update("INSERT INTO currency (currency_id, currency_code, currency_name, decimal_places) VALUES (?, ?, 'Test', 2)",
                currencyId, "T" + randomCode());
        jdbc.update("INSERT INTO cost_center (cost_center_id, department_uuid, cost_center_code, cost_center_name, allow_unbudgeted, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Test', 0, NOW(6), NOW(6))", costCenterId, bytes(UUID.randomUUID()), "CC" + randomCode());
    }

    @AfterEach
    void tearDown() {
        pools.forEach(ExecutorService::shutdownNow);
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM cash_advance_adjustment WHERE advance_id IN (SELECT advance_id FROM cash_advance WHERE employee_id = ?)", EMP);
        jdbc.update("DELETE FROM cash_advance WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM expense_report WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM cost_center WHERE cost_center_id = ?", costCenterId);
        jdbc.update("DELETE FROM currency WHERE currency_id = ?", currencyId);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID advance(String amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_advance (advance_id, currency_id, cost_center_id, employee_id, amount, outstanding_balance, "
                        + "status, expense_deadline_reopened, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'DISBURSED', 0, NOW(6), NOW(6), 0)",
                bytes(id), currencyId, costCenterId, EMP, new BigDecimal(amount), new BigDecimal(amount));
        return id;
    }

    private UUID report() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO expense_report (report_id, employee_id, report_number, title, fiscal_year, cost_center_id, currency_id, "
                        + "invoice_handoff_status, company_paid, expense_deadline_reopened, created_at, updated_at, version, "
                        + "report_status, payment_routing_status, total_amount, reimbursable_amount) "
                        + "VALUES (?, ?, ?, ?, '2026', ?, ?, 'NOT_APPLICABLE', 0, 0, NOW(6), NOW(6), 0, "
                        + "'APPROVED', 'NONE', 5000.0000, 5000.0000)",
                bytes(id), EMP, "CR" + randomCode(), "Concurrency " + randomCode(), costCenterId, currencyId);
        return id;
    }

    private void insertOffset(UUID advanceId, UUID reportId, String amount, boolean reversed) {
        jdbc.update("INSERT INTO cash_advance_adjustment (adjustment_id, advance_id, report_id, adjusted_amount, adjusted_at, adjusted_by, version, reversed) "
                        + "VALUES (?, ?, ?, ?, NOW(6), 'FIN001', 0, ?)",
                bytes(UUID.randomUUID()), bytes(advanceId), bytes(reportId), new BigDecimal(amount), reversed);
    }

    private UUID offsetId(UUID advanceId, UUID reportId) {
        byte[] id = jdbc.queryForObject("SELECT adjustment_id FROM cash_advance_adjustment WHERE advance_id = ? AND report_id = ? AND reversed = 0",
                byte[].class, bytes(advanceId), bytes(reportId));
        return fromBytes(id);
    }

    private BigDecimal storedBalance(UUID advanceId) {
        return jdbc.queryForObject("SELECT outstanding_balance FROM cash_advance WHERE advance_id = ?", BigDecimal.class, bytes(advanceId));
    }

    private BigDecimal activeOffsetSum(UUID advanceId) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(adjusted_amount), 0) FROM cash_advance_adjustment WHERE advance_id = ? AND reversed = 0",
                BigDecimal.class, bytes(advanceId));
        return v;
    }

    private int activeRows(UUID advanceId, UUID reportId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cash_advance_adjustment WHERE advance_id = ? AND report_id = ? AND reversed = 0",
                Integer.class, bytes(advanceId), bytes(reportId));
    }

    // ------------------------------------------------------------------ concurrency helper

    /** Runs each task on its own thread, all released at the same moment, each with the Finance user's identity. */
    private List<Outcome> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        pools.add(pool);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "FIN001", null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_EXECUTIVE"))));
                start.await();
                try {
                    return new Outcome(task.call(), null);
                } catch (Throwable t) {
                    return new Outcome(null, t);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
        }
        start.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private record Outcome(Object value, Throwable error) {
        boolean ok() { return error == null; }
    }

    private static long failures(List<Outcome> outcomes, Class<? extends Throwable> type) {
        return outcomes.stream().filter(o -> o.error() != null && type.isInstance(o.error())).count();
    }

    private static long successes(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::ok).count();
    }

    private Callable<Object> create(UUID advanceId, UUID reportId, String amount) {
        return () -> service.create(new CashAdvanceAdjustmentRequest(advanceId, reportId, new BigDecimal(amount), "FIN001", null));
    }

    private Callable<Object> reverse(UUID adjustmentId, String reason) {
        return () -> service.reverse(adjustmentId, reason);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void twoOffsetsRaceForOneBalance_exactlyOneSucceeds_andTheBalanceNeverGoesNegative() throws Exception {
        UUID a = advance("1000.00");
        UUID r1 = report();
        UUID r2 = report();

        List<Outcome> out = race(List.of(create(a, r1, "600.00"), create(a, r2, "600.00")));

        assertEquals(1, successes(out), "exactly one 600 offset fits in a 1000 balance");
        assertEquals(1, failures(out, BusinessRuleViolationException.class));
        assertEquals(0, new BigDecimal("400.00").compareTo(storedBalance(a)));
        assertEquals(0, new BigDecimal("600.00").compareTo(activeOffsetSum(a)));
        assertTrue(storedBalance(a).signum() >= 0);
    }

    @Test
    void twoReversalsOfTheSameOffset_exactlyOneSucceeds_andTheBalanceIsRestoredOnce() throws Exception {
        UUID a = advance("1000.00");
        UUID r1 = report();
        insertOffset(a, r1, "300.00", false);
        UUID adjustmentId = offsetId(a, r1);

        List<Outcome> out = race(List.of(reverse(adjustmentId, "first"), reverse(adjustmentId, "second")));

        assertEquals(1, successes(out), "only one reversal may succeed");
        assertEquals(1, failures(out, BusinessRuleViolationException.class));
        assertEquals(0, new BigDecimal("1000.00").compareTo(storedBalance(a)), "restored exactly once");
        assertEquals(0, BigDecimal.ZERO.compareTo(activeOffsetSum(a)));
        Integer reversed = jdbc.queryForObject("SELECT COUNT(*) FROM cash_advance_adjustment WHERE adjustment_id = ? AND reversed = 1",
                Integer.class, bytes(adjustmentId));
        assertEquals(1, reversed.intValue(), "the row is kept and marked reversed, not deleted");
        verify(budgetMovements, times(1)).reverseOffset(any(), any(), any());
    }

    @Test
    void createRacingWithReversal_endsWithStoredBalanceEqualToTheActiveRecords() throws Exception {
        UUID a = advance("1000.00");
        UUID r1 = report();
        UUID r2 = report();
        insertOffset(a, r1, "300.00", false);
        // stored balance as it stands before the race: 700
        jdbc.update("UPDATE cash_advance SET outstanding_balance = 700.0000 WHERE advance_id = ?", bytes(a));
        UUID adjustmentId = offsetId(a, r1);

        List<Outcome> out = race(List.of(reverse(adjustmentId, "wrong trip"), create(a, r2, "800.00")));

        // The reversal always succeeds. The 800 offset succeeds only if it ran after the reversal (balance 1000).
        assertTrue(out.get(0).ok() || out.get(1).ok(), "at least one of the two operations must succeed");
        for (Outcome o : out) {
            if (!o.ok()) {
                assertInstanceOf(BusinessRuleViolationException.class, o.error(), "only business rejections are allowed: " + o.error());
            }
        }
        BigDecimal expected = new BigDecimal("1000.00").subtract(activeOffsetSum(a));
        assertEquals(0, expected.compareTo(storedBalance(a)), "stored balance must equal amount minus active offsets");
        assertTrue(storedBalance(a).signum() >= 0);
    }

    @Test
    void twoCreatesForTheSameAdvanceAndReport_exactlyOneActiveRowExists() throws Exception {
        UUID a = advance("1000.00");
        UUID r1 = report();

        List<Outcome> out = race(List.of(create(a, r1, "100.00"), create(a, r1, "100.00")));

        assertEquals(1, successes(out));
        assertEquals(1, failures(out, BusinessRuleViolationException.class));
        assertEquals(1, activeRows(a, r1), "only one active offset per advance and report");
        assertEquals(0, new BigDecimal("900.00").compareTo(storedBalance(a)));
    }

    @Test
    void rejectedOffset_leavesNoPartialChange() {
        UUID a = advance("1000.00");
        UUID r1 = report();
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM cash_advance_adjustment WHERE advance_id = ?", Integer.class, bytes(a));

        assertThrows(BusinessRuleViolationException.class, () -> service.create(new CashAdvanceAdjustmentRequest(a, r1, new BigDecimal("1500.00"), "FIN001", null)));

        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM cash_advance_adjustment WHERE advance_id = ?", Integer.class, bytes(a));
        assertEquals(before, after, "no row was written");
        assertEquals(0, new BigDecimal("1000.00").compareTo(storedBalance(a)), "stored balance unchanged");
        verify(budgetMovements, never()).moveAllocationToUtilized(any(), any(), any());
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static UUID fromBytes(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static String randomCode() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
