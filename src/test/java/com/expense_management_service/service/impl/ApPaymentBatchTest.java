package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.ApPaymentBatchRequest;
import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.dto.response.ApPaymentBatchResponse;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentMethod;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.repository.ApPaymentAllocationRepository;
import com.expense_management_service.repository.ApPaymentBatchRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.CostCenterBudgetService;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Decision 3: batch payments. One bank reference per batch, per-report allocations, budget consumed once at completion. */
@ExtendWith(MockitoExtension.class)
class ApPaymentBatchTest {

    private static final String REF = "BATCH-REF-000001";

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ApPaymentBatchRepository apPaymentBatchRepository;
    @Mock private ApPaymentAllocationRepository apPaymentAllocationRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private BudgetEncumbranceService budgetEncumbranceService;
    @Mock private CostCenterBudgetService costCenterBudgetService;
    @Mock private ExpenseLineItemService expenseLineItemService;
    @Mock private CashAdvanceBalanceLedger cashAdvanceBalanceLedger;
    @Mock private com.expense_management_service.repository.CashAdvanceRepository cashAdvanceRepository;
    @Mock private com.expense_management_service.service.impl.ExpenseReportResponseFactory expenseReportResponseFactory;

    @InjectMocks private ApPaymentServiceImpl service;

    private UUID r1;
    private UUID r2;
    private ExpenseReport report1;
    private ExpenseReport report2;

    @BeforeEach
    void setUp() {
        r1 = UUID.randomUUID();
        r2 = UUID.randomUUID();
        report1 = payable(r1, "ER-1", "1000.00", null);
        report2 = payable(r2, "ER-2", "500.00", null);
        lenient().when(expenseReportRepository.findByIdForUpdate(r1)).thenReturn(Optional.of(report1));
        lenient().when(expenseReportRepository.findByIdForUpdate(r2)).thenReturn(Optional.of(report2));
        lenient().when(approvalWorkflowService.getCurrentSubmissionCycle(any())).thenReturn(1);
        lenient().when(budgetEncumbranceService.consumeActiveForReport(any(), anyInt())).thenReturn(true);
        lenient().when(apPaymentBatchRepository.save(any())).thenAnswer(inv -> {
            var b = inv.<com.expense_management_service.entity.ApPaymentBatch>getArgument(0);
            if (b.getBatchId() == null) b.setBatchId(UUID.randomUUID());
            return b;
        });
    }

    private static ExpenseReport payable(UUID id, String number, String owed, String paid) {
        return ExpenseReport.builder()
                .reportId(id)
                .reportNumber(number)
                .employeeId("EMP001")
                .title("Trip")
                .totalAmount(new BigDecimal(owed))
                .reimbursableAmount(new BigDecimal(owed))
                .paidAmount(paid == null ? null : new BigDecimal(paid))
                .paymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT)
                .build();
    }

    private static ApPaymentBatchRequest batch(String reference, String total, ApPaymentBatchRequest.Allocation... allocations) {
        return new ApPaymentBatchRequest(reference, PaymentMethod.BANK_TRANSFER, LocalDate.now(),
                new BigDecimal(total), "monthly run", List.of(allocations));
    }

    private static ApPaymentBatchRequest.Allocation alloc(UUID report, String amount) {
        return new ApPaymentBatchRequest.Allocation(report, new BigDecimal(amount));
    }

    // ------------------------------------------------------------------ shape and reference rules

    @Test
    void totalMustEqualTheSumOfTheAllocations() {
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "900.00", alloc(r1, "400.00"), alloc(r2, "400.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void aReportCannotAppearTwiceInOneBatch() {
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "300.00", alloc(r1, "100.00"), alloc(r1, "200.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
    }

    @Test
    void aBankReferenceAlreadyUsedByABatch_isRefused() {
        when(apPaymentBatchRepository.existsByBankReference(REF)).thenReturn(true);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
    }

    @Test
    void aBankReferenceAlreadyRecordedOnAReport_isRefused_becauseItIsAnotherTransaction() {
        when(expenseReportRepository.existsByPaymentReference(REF)).thenReturn(true);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ report rules

    @Test
    void anAllocationAboveTheRemainingPayable_isRefused_andNothingIsWritten() {
        report1.setPaidAmount(new BigDecimal("800.00"));   // 200 left
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "300.00", alloc(r1, "300.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
        verify(apPaymentAllocationRepository, never()).save(any());
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void aReportNotWaitingForPayment_isRefused() {
        report1.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001"));
        verify(apPaymentBatchRepository, never()).save(any());
    }

    @Test
    void anApExecutiveCannotPayTheirOwnReport() {
        report1.setEmployeeId("AP001");
        assertThrows(AccessDeniedException.class,
                () -> service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001"));
    }

    @Test
    void reportsAreLockedInAFixedOrder_soConcurrentBatchesCannotDeadlock() {
        service.recordBatchPayment(batch(REF, "1000.00", alloc(r2, "500.00"), alloc(r1, "500.00")), "AP001");
        var order = org.mockito.Mockito.inOrder(expenseReportRepository);
        // ids are locked sorted by their string form, regardless of the order in the request
        UUID first = r1.toString().compareTo(r2.toString()) < 0 ? r1 : r2;
        UUID second = first.equals(r1) ? r2 : r1;
        order.verify(expenseReportRepository).findByIdForUpdate(first);
        order.verify(expenseReportRepository).findByIdForUpdate(second);
    }

    // ------------------------------------------------------------------ partial and full allocations

    @Test
    void aPartialAllocation_leavesTheReportPayable_andConsumesNoBudget() {
        ApPaymentBatchResponse response = service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001");

        assertEquals(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, report1.getPaymentRoutingStatus(), "not marked paid");
        assertEquals(0, new BigDecimal("400.00").compareTo(report1.getPaidAmount()));
        assertFalse(response.allocations().get(0).reportPaid());
        assertEquals(0, new BigDecimal("600.00").compareTo(response.allocations().get(0).payableAfter()), "600 still payable");
        verify(approvalWorkflowService, never()).getCurrentSubmissionCycle(any());
        verify(budgetEncumbranceService, never()).consumeActiveForReport(any(), anyInt());
    }

    @Test
    void theFinalAllocation_completesTheReport_andConsumesBudgetExactlyOnce() {
        report1.setPaidAmount(new BigDecimal("400.00"));   // 600 left
        ApPaymentBatchResponse response = service.recordBatchPayment(batch(REF, "600.00", alloc(r1, "600.00")), "AP001");

        assertEquals(PaymentRoutingStatus.PAYMENT_COMPLETED, report1.getPaymentRoutingStatus());
        assertEquals(0, new BigDecimal("1000.00").compareTo(report1.getPaidAmount()));
        assertEquals(REF, report1.getPaymentReference());
        assertTrue(response.allocations().get(0).reportPaid());
        verify(budgetEncumbranceService, times(1)).consumeActiveForReport(eq(r1), eq(1));
    }

    @Test
    void oneBatch_paysSeveralReports_eachWithItsOwnAllocation_andTheSameReference() {
        ApPaymentBatchResponse response = service.recordBatchPayment(
                batch(REF, "1500.00", alloc(r1, "1000.00"), alloc(r2, "500.00")), "AP001");

        assertEquals(PaymentRoutingStatus.PAYMENT_COMPLETED, report1.getPaymentRoutingStatus());
        assertEquals(PaymentRoutingStatus.PAYMENT_COMPLETED, report2.getPaymentRoutingStatus());
        assertEquals(REF, report1.getPaymentReference());
        assertEquals(REF, report2.getPaymentReference());
        assertEquals(2, response.allocations().size());
        verify(apPaymentAllocationRepository, times(2)).save(any());
        verify(apPaymentBatchRepository, times(1)).save(any());
    }

    @Test
    void auditTrailRecordsEachAllocationAndTheBatch() {
        service.recordBatchPayment(batch(REF, "400.00", alloc(r1, "400.00")), "AP001");

        verify(auditLogRepository).save(argThatAction("AP_BATCH_PAYMENT_ALLOCATED"));
        verify(auditLogRepository).save(argThatAction("AP_BATCH_RECORDED"));
    }

    private static com.expense_management_service.entity.AuditLog argThatAction(String action) {
        return org.mockito.ArgumentMatchers.argThat(a -> a != null && action.equals(a.getAction()));
    }

    // ------------------------------------------------------------------ the single-payment path cannot double-pay a partly paid report

    @Test
    void singlePayment_isRefused_onAReportAlreadyPartlyPaidByABatch() {
        report1.setPaidAmount(new BigDecimal("400.00"));
        CompletePaymentRequest single = new CompletePaymentRequest("SINGLE-REF-0001", PaymentMethod.BANK_TRANSFER,
                LocalDate.now(), new BigDecimal("1000.00"), null);
        when(expenseReportRepository.findByIdForUpdate(r1)).thenReturn(Optional.of(report1));

        assertThrows(BusinessRuleViolationException.class, () -> service.markPaymentCompleted(r1, single, "AP001"));
        assertEquals(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, report1.getPaymentRoutingStatus());
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void singlePayment_isRefused_whenItsReferenceWasAlreadyUsedByABatch() {
        when(apPaymentBatchRepository.existsByBankReference("SINGLE-REF-0002")).thenReturn(true);
        CompletePaymentRequest single = new CompletePaymentRequest("SINGLE-REF-0002", PaymentMethod.BANK_TRANSFER,
                LocalDate.now(), new BigDecimal("1000.00"), null);

        assertThrows(BusinessRuleViolationException.class, () -> service.markPaymentCompleted(r1, single, "AP001"));
        verify(expenseReportRepository, never()).save(any());
    }
}
