package com.expense_management_service.concurrency;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.mapper.CashAdvanceMapper;
import com.expense_management_service.mapper.CashAdvanceRepaymentMapper;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.impl.CashAdvanceBalanceCalculator;
import com.expense_management_service.service.impl.CashAdvanceBalanceLedger;
import com.expense_management_service.service.impl.CashAdvanceBalanceRules;
import com.expense_management_service.service.impl.CashAdvanceBudgetAllocator;
import com.expense_management_service.service.impl.CashAdvanceBudgetMovements;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import com.expense_management_service.service.impl.CashAdvanceEmailService;
import com.expense_management_service.service.impl.CashAdvanceRecoveryService;
import com.expense_management_service.service.impl.CashAdvanceServiceImpl;
import com.expense_management_service.service.impl.CashAdvanceUtilizationCalculator;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.storage.StorageService;
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
import java.time.LocalDate;
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
import static org.mockito.Mockito.when;

/**
 * Real-MySQL 8 concurrency for cash advance disbursement and repayment, through the real CashAdvanceServiceImpl.
 * Runs only when CA_IT_MYSQL_URL points at a disposable database whose name contains {@code ca_it}.
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
@Import({CashAdvanceServiceImpl.class, CashAdvanceMapper.class, CashAdvanceRepaymentMapper.class,
        CashAdvanceAdjustmentMapper.class, CashAdvanceBalanceCalculator.class, CashAdvanceDisbursementRepaymentMysqlTest.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "CA_IT_MYSQL_URL", matches = ".*ca_it.*")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CashAdvanceDisbursementRepaymentMysqlTest {

    private static final String EMP = "CONC-PAY";

    @MockitoBean CashAdvanceBudgetMovements budgetMovements;
    @MockitoBean CashAdvanceRecoveryService recoveryService;
    @MockitoBean DelegationService delegationService;
    @MockitoBean CurrentUserService currentUserService;
    @MockitoBean CashAdvanceCarryForwardService carryForwardService;
    @MockitoBean ApprovalEventPublisher approvalEventPublisher;
    @MockitoBean CashAdvanceBudgetAllocator budgetAllocator;
    @MockitoBean StorageService storageService;
    @MockitoBean CashAdvanceBalanceLedger balanceLedger;
    @MockitoBean CashAdvanceEmailService emailService;
    @MockitoBean CashAdvanceUtilizationCalculator utilizationCalculator;

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemDefaultZone();
        }
    }

    @Autowired CashAdvanceServiceImpl service;
    @Autowired JdbcTemplate jdbc;

    private final List<ExecutorService> pools = new ArrayList<>();
    private byte[] currencyId;
    private byte[] costCenterId;

    @BeforeEach
    void setUp() {
        assertTrue(System.getenv("CA_IT_MYSQL_URL").contains("ca_it"), "Refusing to run outside a ca_it test database");
        when(currentUserService.getEmployeeId()).thenReturn("FIN001");
        when(balanceLedger.statusFor(any(), any(), anyInt()))
                .thenAnswer(inv -> CashAdvanceBalanceRules.statusAfterBalance(inv.<BigDecimal>getArgument(1), inv.<Integer>getArgument(2)));
        currencyId = bytes(UUID.randomUUID());
        costCenterId = bytes(UUID.randomUUID());
        jdbc.update("INSERT INTO currency (currency_id, currency_code, currency_name, decimal_places) VALUES (?, ?, 'Test', 2)",
                currencyId, "T" + code());
        jdbc.update("INSERT INTO cost_center (cost_center_id, department_uuid, cost_center_code, cost_center_name, allow_unbudgeted, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Test', 0, NOW(6), NOW(6))", costCenterId, bytes(UUID.randomUUID()), "CC" + code());
    }

    @AfterEach
    void tearDown() {
        pools.forEach(ExecutorService::shutdownNow);
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM cash_advance_repayment WHERE advance_id IN (SELECT advance_id FROM cash_advance WHERE employee_id = ?)", EMP);
        jdbc.update("DELETE FROM cash_advance WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM cost_center WHERE cost_center_id = ?", costCenterId);
        jdbc.update("DELETE FROM currency WHERE currency_id = ?", currencyId);
    }

    private UUID advance(String status, String amount, String outstanding) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_advance (advance_id, currency_id, cost_center_id, employee_id, amount, approved_amount, outstanding_balance, "
                        + "status, expense_deadline_reopened, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NOW(6), NOW(6), 0)",
                bytes(id), currencyId, costCenterId, EMP, new BigDecimal(amount), new BigDecimal(amount),
                outstanding == null ? null : new BigDecimal(outstanding), status);
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM cash_advance WHERE advance_id = ?", String.class, bytes(id));
    }

    private BigDecimal stored(UUID id) {
        return jdbc.queryForObject("SELECT outstanding_balance FROM cash_advance WHERE advance_id = ?", BigDecimal.class, bytes(id));
    }

    private int repaymentRows(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cash_advance_repayment WHERE advance_id = ?", Integer.class, bytes(id));
    }

    private static CashAdvanceDisbursement bankTransfer(String reference) {
        return new CashAdvanceDisbursement("BANK_TRANSFER", null, null, reference, LocalDate.now(), "NEFT", null, null, null, null);
    }

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
        List<Outcome> out = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            out.add(f.get(60, TimeUnit.SECONDS));
        }
        return out;
    }

    private record Outcome(Object value, Throwable error) {
        boolean ok() { return error == null; }
    }

    private static long successes(List<Outcome> o) { return o.stream().filter(Outcome::ok).count(); }

    private static long businessRejections(List<Outcome> o) {
        return o.stream().filter(x -> x.error() instanceof BusinessRuleViolationException).count();
    }

    // ------------------------------------------------------------------ disbursement

    @Test
    void twoDisbursementsOfOneApprovedAdvance_exactlyOnePaysOut() throws Exception {
        UUID a = advance("APPROVED", "1000.00", null);

        List<Outcome> out = race(List.of(
                () -> service.disburse(a, "FIN001", bankTransfer("UTR-AAAA-0001")),
                () -> service.disburse(a, "FIN001", bankTransfer("UTR-BBBB-0002"))));

        assertEquals(1, successes(out), "exactly one disbursement may succeed");
        assertEquals(1, businessRejections(out), "the other is refused as already disbursed or not approved");
        assertEquals("IN_PROGRESS", status(a));
        Integer paidTwice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'CASH_ADVANCE_DISBURSED'", Integer.class, bytes(a));
        assertEquals(1, paidTwice.intValue(), "one disbursement is recorded in the audit trail");
    }

    // ------------------------------------------------------------------ repayments

    @Test
    void twoRepaymentsThatTogetherExceedTheBalance_theSecondIsRefused_andTheBalanceNeverGoesNegative() throws Exception {
        UUID a = advance("DISBURSED", "1000.00", "1000.00");

        List<Outcome> out = race(List.of(
                () -> service.repay(new CashAdvanceRepaymentRequest(a, new BigDecimal("600.00"), "first", bankTransfer("RPY-AAAA-0001"))),
                () -> service.repay(new CashAdvanceRepaymentRequest(a, new BigDecimal("600.00"), "second", bankTransfer("RPY-BBBB-0002")))));

        assertEquals(1, successes(out), "only one 600 repayment fits in a 1000 balance");
        assertEquals(1, businessRejections(out));
        assertEquals(0, new BigDecimal("400.00").compareTo(stored(a)));
        assertEquals(1, repaymentRows(a), "the refused repayment left no row");
        assertTrue(stored(a).signum() >= 0);
    }

    @Test
    void twoRepaymentsWithTheSameReference_areRecordedOnce() throws Exception {
        UUID a = advance("DISBURSED", "1000.00", "1000.00");

        List<Outcome> out = race(List.of(
                () -> service.repay(new CashAdvanceRepaymentRequest(a, new BigDecimal("100.00"), "x", bankTransfer("RPY-SAME-0009"))),
                () -> service.repay(new CashAdvanceRepaymentRequest(a, new BigDecimal("100.00"), "y", bankTransfer("RPY-SAME-0009")))));

        assertEquals(1, successes(out), "a repeated reference must not post twice");
        assertEquals(1, repaymentRows(a));
        assertEquals(0, new BigDecimal("900.00").compareTo(stored(a)), "the money moved once");
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String code() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
