package com.expense_management_service.concurrency;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.ApPaymentBatchRequest;
import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.enums.PaymentMethod;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.CostCenterBudgetService;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.service.impl.ApPaymentServiceImpl;
import com.expense_management_service.service.impl.CashAdvanceBalanceLedger;
import com.expense_management_service.service.impl.ExpenseReportResponseFactory;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Real-MySQL 8 concurrency for AP payments: duplicate single confirmations, batches racing for the same payable amount,
 * and batches reusing one bank reference. Runs only when CA_IT_MYSQL_URL points at a disposable ca_it database.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "exchange.rate.base-currency=INR"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${CA_IT_MYSQL_URL:}",
        "spring.datasource.username=${CA_IT_MYSQL_USER:}",
        "spring.datasource.password=${CA_IT_MYSQL_PASSWORD:}"
})
@Import({ApPaymentServiceImpl.class, ExpenseReportResponseFactory.class, ExpenseReportMapper.class,
        ApPaymentConcurrencyMysqlTest.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "CA_IT_MYSQL_URL", matches = ".*ca_it.*")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApPaymentConcurrencyMysqlTest {

    private static final String EMP = "CONC-AP-EMP";

    @MockitoBean ExpenseLineItemService expenseLineItemService;
    @MockitoBean ApprovalWorkflowService approvalWorkflowService;
    @MockitoBean ApprovalEventPublisher approvalEventPublisher;
    @MockitoBean CostCenterBudgetService costCenterBudgetService;
    @MockitoBean BudgetEncumbranceService budgetEncumbranceService;
    @MockitoBean CashAdvanceBalanceLedger cashAdvanceBalanceLedger;

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemDefaultZone();
        }
    }

    @Autowired ApPaymentServiceImpl service;
    @Autowired JdbcTemplate jdbc;

    private final List<ExecutorService> pools = new ArrayList<>();
    private byte[] currencyId;
    private byte[] costCenterId;

    @BeforeEach
    void setUp() {
        assertTrue(System.getenv("CA_IT_MYSQL_URL").contains("ca_it"), "Refusing to run outside a ca_it test database");
        when(approvalWorkflowService.getCurrentSubmissionCycle(any())).thenReturn(1);
        when(budgetEncumbranceService.consumeActiveForReport(any(), anyInt())).thenReturn(true);
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
        jdbc.update("DELETE FROM ap_payment_allocation WHERE report_id IN (SELECT report_id FROM expense_report WHERE employee_id = ?)", EMP);
        jdbc.update("DELETE FROM ap_payment_batch WHERE batch_id NOT IN (SELECT batch_id FROM ap_payment_allocation)");
        jdbc.update("DELETE FROM expense_report WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM cost_center WHERE cost_center_id = ?", costCenterId);
        jdbc.update("DELETE FROM currency WHERE currency_id = ?", currencyId);
    }

    private UUID report(String owed) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO expense_report (report_id, employee_id, report_number, title, fiscal_year, cost_center_id, currency_id, "
                        + "invoice_handoff_status, company_paid, expense_deadline_reopened, created_at, updated_at, version, "
                        + "report_status, payment_routing_status, total_amount, reimbursable_amount) "
                        + "VALUES (?, ?, ?, ?, '2026', ?, ?, 'NOT_APPLICABLE', 0, 0, NOW(6), NOW(6), 0, "
                        + "'APPROVED', 'APPROVED_FOR_PAYMENT', ?, ?)",
                bytes(id), EMP, "AP" + code(), "AP payment " + code(), costCenterId, currencyId,
                new BigDecimal(owed), new BigDecimal(owed));
        return id;
    }

    private BigDecimal paid(UUID reportId) {
        return jdbc.queryForObject("SELECT COALESCE(paid_amount, 0) FROM expense_report WHERE report_id = ?", BigDecimal.class, bytes(reportId));
    }

    private String routing(UUID reportId) {
        return jdbc.queryForObject("SELECT payment_routing_status FROM expense_report WHERE report_id = ?", String.class, bytes(reportId));
    }

    private int allocationRows(UUID reportId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ap_payment_allocation WHERE report_id = ?", Integer.class, bytes(reportId));
    }

    private static CompletePaymentRequest single(String ref, String amount) {
        return new CompletePaymentRequest(ref, PaymentMethod.BANK_TRANSFER, LocalDate.now(), new BigDecimal(amount), null);
    }

    private static ApPaymentBatchRequest batch(String ref, UUID report, String amount) {
        return new ApPaymentBatchRequest(ref, PaymentMethod.BANK_TRANSFER, LocalDate.now(), new BigDecimal(amount), null,
                List.of(new ApPaymentBatchRequest.Allocation(report, new BigDecimal(amount))));
    }

    private List<Outcome> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        pools.add(pool);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "AP001", null, List.of(new SimpleGrantedAuthority("ROLE_AP_EXECUTIVE"))));
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

    private static long refused(List<Outcome> o) {
        return o.stream().filter(x -> x.error() instanceof BusinessRuleViolationException
                || x.error() instanceof IllegalArgumentException
                || x.error() instanceof org.springframework.dao.DataIntegrityViolationException
                || x.error() instanceof org.springframework.orm.ObjectOptimisticLockingFailureException).count();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void duplicateSingleConfirmations_exactlyOneRecordsThePayment_andBudgetIsConsumedOnce() throws Exception {
        UUID r = report("1000.00");

        List<Outcome> out = race(List.of(
                () -> service.markPaymentCompleted(r, single("UTR-SINGLE-A1", "1000.00"), "AP001"),
                () -> service.markPaymentCompleted(r, single("UTR-SINGLE-B2", "1000.00"), "AP001")));

        assertEquals(1, successes(out), "exactly one confirmation may succeed");
        assertEquals(1, refused(out), "the other is refused");
        assertEquals("PAYMENT_COMPLETED", routing(r));
        assertEquals(0, new BigDecimal("1000.00").compareTo(paid(r)));
        verify(budgetEncumbranceService, times(1)).consumeActiveForReport(any(), anyInt());
    }

    @Test
    void twoBatchesRacingForTheSamePayableAmount_exactlyOneAllocates_andNeverOverpays() throws Exception {
        UUID r = report("1000.00");

        List<Outcome> out = race(List.of(
                () -> service.recordBatchPayment(batch("UTR-BATCH-A001", r, "700.00"), "AP001"),
                () -> service.recordBatchPayment(batch("UTR-BATCH-B002", r, "700.00"), "AP001")));

        assertEquals(1, successes(out), "700 + 700 cannot both fit in 1000");
        assertEquals(1, refused(out));
        assertEquals(0, new BigDecimal("700.00").compareTo(paid(r)), "paid exactly once");
        assertEquals("APPROVED_FOR_PAYMENT", routing(r), "still payable: 300 remains");
        assertEquals(1, allocationRows(r), "the refused batch wrote no allocation");
    }

    @Test
    void twoBatchesWithTheSameBankReference_exactlyOneIsRecorded_andTheOtherLeavesNoTrace() throws Exception {
        UUID r1 = report("500.00");
        UUID r2 = report("500.00");

        List<Outcome> out = race(List.of(
                () -> service.recordBatchPayment(batch("UTR-SAMEREF-0001", r1, "500.00"), "AP001"),
                () -> service.recordBatchPayment(batch("UTR-SAMEREF-0001", r2, "500.00"), "AP001")));

        assertEquals(1, successes(out), "one bank reference, one batch");
        assertEquals(1, refused(out));
        Integer batches = jdbc.queryForObject("SELECT COUNT(*) FROM ap_payment_batch WHERE bank_reference = 'UTR-SAMEREF-0001'", Integer.class);
        assertEquals(1, batches.intValue());
        // the refused batch's report was not touched, even though its lock was taken
        int untouched = (routing(r1).equals("PAYMENT_COMPLETED") ? 0 : 1) + (routing(r2).equals("PAYMENT_COMPLETED") ? 0 : 1);
        assertEquals(1, untouched, "exactly one of the two reports was paid");
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String code() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
