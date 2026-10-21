package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.enums.PaymentMethod;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.CostCenterBudgetService;
import com.expense_management_service.service.ExpenseLineItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class ApPaymentServiceImplTest {

    @Mock private ExpenseReportRepository expenseReportRepository;
    @Mock private ExpenseLineItemService expenseLineItemService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private ApprovalEventPublisher approvalEventPublisher;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private PolicyViolationRepository policyViolationRepository;
    @Mock private CostCenterBudgetService costCenterBudgetService;
    @Mock private BudgetEncumbranceService budgetEncumbranceService;

    private ApPaymentServiceImpl service;

    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var factory = new ExpenseReportResponseFactory(new com.expense_management_service.mapper.ExpenseReportMapper(), policyViolationRepository);
        service = new ApPaymentServiceImpl(expenseReportRepository, expenseLineItemService, approvalWorkflowService,
                approvalEventPublisher, auditLogRepository, factory, costCenterBudgetService, budgetEncumbranceService);
        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
    }

    private ExpenseReport report(ReportStatus reportStatus, PaymentRoutingStatus paymentRoutingStatus) {
        return ExpenseReport.builder().reportId(reportId).reportNumber("EXP-1").employeeId("5100001")
                .reportStatus(reportStatus).paymentRoutingStatus(paymentRoutingStatus)
                .totalAmount(new BigDecimal("12500.00")).reimbursableAmount(new BigDecimal("12500.00")).build();
    }

    private ExpenseReport approvedForPaymentReportWithBudgetFields() {
        CostCenter costCenter = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-1").build();
        return ExpenseReport.builder().reportId(reportId).reportNumber("EXP-1").employeeId("5100001")
                .reportStatus(ReportStatus.APPROVED).paymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT)
                .costCenter(costCenter).fiscalYear("2026").totalAmount(new BigDecimal("12500.00"))
                .reimbursableAmount(new BigDecimal("12500.00")).build();
    }

    /** AP's confirmation of the actual payment - the amount owed on the fixtures above. */
    private static CompletePaymentRequest payment() {
        return payment(new BigDecimal("12500.00"));
    }

    private static CompletePaymentRequest payment(BigDecimal amount) {
        return new CompletePaymentRequest("UTR123456789", PaymentMethod.BANK_TRANSFER, LocalDate.now(), amount, "Paid via NEFT");
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - valid transition
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_succeeds_whenApprovedForPayment() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReportResponse response = service.markPaymentCompleted(reportId, payment(), "5100099");

        assertThat(response).isNotNull();
        assertThat(response.paymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.PAYMENT_COMPLETED.name());
        assertThat(report.getPaymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.PAYMENT_COMPLETED);
        assertThat(report.getPaymentCompletedBy()).isEqualTo("5100099");
        assertThat(report.getPaymentCompletedAt()).isNotNull();
        assertThat(report.getPaymentReference()).isEqualTo("UTR123456789");
        assertThat(report.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(report.getPaidAmount()).isEqualByComparingTo("12500.00");
        assertThat(response.paymentConfirmation()).isNotNull();
        assertThat(response.paymentConfirmation().paymentReference()).isEqualTo("UTR123456789");
        verify(approvalEventPublisher).publish(eq("PAYMENT_COMPLETED"), eq(reportId), any());
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - the confirmation must match the actual payment owed
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_rejectsAnAmountThatDoesNotMatchWhatIsOwed_andLeavesTheReportUnpaid() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        report.setReimbursableAmount(new BigDecimal("9500.00")); // Finance offset a cash advance after AP opened it
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(new BigDecimal("12500.00")), "5100099"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("doesn't match the 9500.00");

        assertThat(report.getPaymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        verify(expenseReportRepository, never()).save(any());
        verify(approvalEventPublisher, never()).publish(eq("PAYMENT_COMPLETED"), any(), any());
    }

    @Test
    void markPaymentCompleted_rejectsAnApExecutiveConfirmingTheirOwnReport() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100001"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void markPaymentCompleted_rejectsAPaymentDateBeforeApproval() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        report.setApprovedAt(java.time.LocalDateTime.now());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        CompletePaymentRequest backdated = new CompletePaymentRequest("UTR1", PaymentMethod.UPI,
                LocalDate.now().minusDays(3), new BigDecimal("12500.00"), null);

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, backdated, "5100099"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("before the report was approved");
    }

    @Test
    void markPaymentCompleted_rejectsAReportWithNothingOwed() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        report.setReimbursableAmount(BigDecimal.ZERO);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("nothing to pay");
    }

    @Test
    void markPaymentCompleted_writesAuditLogEntry() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPaymentCompleted(reportId, payment(), "5100099");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityName()).isEqualTo("ExpenseReport");
        assertThat(saved.getEntityId()).isEqualTo(reportId);
        assertThat(saved.getAction()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(saved.getOldValue()).isEqualTo("APPROVED_FOR_PAYMENT");
        assertThat(saved.getNewValue()).startsWith("PAYMENT_COMPLETED").contains("reference=UTR123456789")
                .contains("method=BANK_TRANSFER").contains("amount=12500.00");
        assertThat(saved.getReason()).isEqualTo("Paid via NEFT");
        assertThat(saved.getPerformedBy()).isEqualTo("5100099");
        assertThat(saved.getPerformedAt()).isNotNull();
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - Cost Center budget deduction
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_consumesBudget_exactlyOnce_withTheReportsCostCenterFiscalYearAndTotalAmount() {
        ExpenseReport report = approvedForPaymentReportWithBudgetFields();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPaymentCompleted(reportId, payment(), "5100099");

        verify(costCenterBudgetService, times(1))
                .consumeBudget(report.getCostCenter(), "2026", new BigDecimal("12500.00"));
    }

    @Test
    void markPaymentCompleted_rollsBackReportChanges_whenBudgetConsumptionFails() {
        ExpenseReport report = approvedForPaymentReportWithBudgetFields();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        doThrow(new IllegalStateException("concurrent budget update conflict"))
                .when(costCenterBudgetService).consumeBudget(any(), any(), any());

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalStateException.class);

        // Budget consumption happens before the report is mutated/saved, so a failure there must
        // leave the report untouched - reportStatus/paymentRoutingStatus stay exactly as they were,
        // and the (would-be) transactional rollback has nothing besides this in-memory entity to
        // undo. Persisting the PAYMENT_COMPLETED fields never happens on this failing path.
        assertThat(report.getPaymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        assertThat(report.getPaymentCompletedBy()).isNull();
        assertThat(report.getPaymentCompletedAt()).isNull();
        verify(expenseReportRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
        verify(approvalEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void markPaymentCompleted_calledTwice_consumesBudgetOnlyOnce_andSecondCallThrows() {
        ExpenseReport report = approvedForPaymentReportWithBudgetFields();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPaymentCompleted(reportId, payment(), "5100099");
        // Second attempt against the same (now PAYMENT_COMPLETED) report - the existing
        // previous != APPROVED_FOR_PAYMENT guard must reject it before ever reaching consumeBudget.
        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100088"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(costCenterBudgetService, times(1)).consumeBudget(any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - budget consumption via BudgetEncumbrance (Phase 6)
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_consumesViaEncumbrances_andSkipsTheFallbackDirectConsumeBudgetCall_whenAnyExist() {
        ExpenseReport report = approvedForPaymentReportWithBudgetFields();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.getCurrentSubmissionCycle(reportId)).thenReturn(2);
        when(budgetEncumbranceService.consumeActiveForReport(reportId, 2)).thenReturn(true);

        service.markPaymentCompleted(reportId, payment(), "5100099");

        verify(budgetEncumbranceService).consumeActiveForReport(reportId, 2);
        verify(costCenterBudgetService, never()).consumeBudget(any(), any(), any());
    }

    @Test
    void markPaymentCompleted_fallsBackToDirectConsumeBudget_whenReportHasNoEncumbrancesAtAll() {
        // Models an in-flight report that reached APPROVED_FOR_PAYMENT before Phase 5's encumbrance
        // wiring existed - consumeActiveForReport correctly finds nothing (returns false), and budget
        // must still be consumed exactly once via the original, pre-Phase-3 direct path.
        ExpenseReport report = approvedForPaymentReportWithBudgetFields();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.getCurrentSubmissionCycle(reportId)).thenReturn(1);
        when(budgetEncumbranceService.consumeActiveForReport(reportId, 1)).thenReturn(false);

        service.markPaymentCompleted(reportId, payment(), "5100099");

        verify(budgetEncumbranceService).consumeActiveForReport(reportId, 1);
        verify(costCenterBudgetService, times(1))
                .consumeBudget(report.getCostCenter(), "2026", new BigDecimal("12500.00"));
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - a split is honoured on the direct (non-encumbrance) paths
    // ---------------------------------------------------------------------

    /** Rs 500 restaurant bill for an Engineering + HR event, split 250 / 250, on a report charged to Engineering. */
    private ExpenseReport splitReport(String reimbursable, CostCenter engineering, CostCenter hr) {
        ExpenseLineItem lunch = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).baseAmount(new BigDecimal("500.00"))
                .costCenter(engineering).build();
        lunch.getExpenseSplits().add(ExpenseSplit.builder().splitId(UUID.randomUUID()).lineItem(lunch)
                .costCenter(engineering).allocatedAmount(new BigDecimal("250.00")).build());
        lunch.getExpenseSplits().add(ExpenseSplit.builder().splitId(UUID.randomUUID()).lineItem(lunch)
                .costCenter(hr).allocatedAmount(new BigDecimal("250.00")).build());
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).reportNumber("EXP-1").employeeId("5100001")
                .reportStatus(ReportStatus.APPROVED).paymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT)
                .costCenter(engineering).fiscalYear("2026").totalAmount(new BigDecimal("500.00"))
                .reimbursableAmount(new BigDecimal(reimbursable)).build();
        report.getExpenseLineItems().add(lunch);
        return report;
    }

    @Test
    void markPaymentCompleted_advanceLinkedSplitReport_chargesEachCostCenterItsShare() {
        CostCenter engineering = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("ENG").build();
        CostCenter hr = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("HR").build();
        ExpenseReport report = splitReport("500.00", engineering, hr);
        report.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).build());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.getCurrentSubmissionCycle(reportId)).thenReturn(1);

        service.markPaymentCompleted(reportId, payment(new BigDecimal("500.00")), "5100099");

        verify(costCenterBudgetService).consumeBudget(engineering, "2026", new BigDecimal("250.00"));
        verify(costCenterBudgetService).consumeBudget(hr, "2026", new BigDecimal("250.00"));
        verify(costCenterBudgetService, times(2)).consumeBudget(any(), any(), any());
    }

    @Test
    void markPaymentCompleted_advanceLinkedSplitReport_proRatesWhatIsLeftAfterTheOffset() {
        // Rs 300 of the Rs 500 was offset against the advance; AP pays the remaining Rs 200.
        CostCenter engineering = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("ENG").build();
        CostCenter hr = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("HR").build();
        ExpenseReport report = splitReport("200.00", engineering, hr);
        report.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).build());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.getCurrentSubmissionCycle(reportId)).thenReturn(1);

        service.markPaymentCompleted(reportId, payment(new BigDecimal("200.00")), "5100099");

        verify(costCenterBudgetService).consumeBudget(engineering, "2026", new BigDecimal("100.00"));
        verify(costCenterBudgetService).consumeBudget(hr, "2026", new BigDecimal("100.00"));
        verify(costCenterBudgetService, times(2)).consumeBudget(any(), any(), any());
    }

    @Test
    void markPaymentCompleted_fallbackPath_alsoFollowsTheSplit() {
        CostCenter engineering = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("ENG").build();
        CostCenter hr = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("HR").build();
        ExpenseReport report = splitReport("500.00", engineering, hr);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));
        when(approvalWorkflowService.getCurrentSubmissionCycle(reportId)).thenReturn(1);
        when(budgetEncumbranceService.consumeActiveForReport(reportId, 1)).thenReturn(false);

        service.markPaymentCompleted(reportId, payment(new BigDecimal("500.00")), "5100099");

        verify(costCenterBudgetService).consumeBudget(engineering, "2026", new BigDecimal("250.00"));
        verify(costCenterBudgetService).consumeBudget(hr, "2026", new BigDecimal("250.00"));
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - invalid transitions (spec §13 AP-status tests)
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_throws_whenAlreadyPaymentCompleted() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(expenseReportRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void markPaymentCompleted_throws_whenDraft() {
        ExpenseReport report = report(ReportStatus.DRAFT, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenPendingApproval() {
        ExpenseReport report = report(ReportStatus.PENDING_APPROVAL, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenPendingFinanceVerification() {
        ExpenseReport report = report(ReportStatus.PENDING_FINANCE_VERIFICATION, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenAwaitingCorrection() {
        ExpenseReport report = report(ReportStatus.AWAITING_CORRECTION, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenApprovedButPaymentRoutingStatusNone() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenInvoiceHandoffPending() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.INVOICE_HANDOFF_PENDING);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenReportNotFound() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, payment(), "5100099"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------------
    // getApQueue / getPaymentDetails
    // ---------------------------------------------------------------------

    @Test
    void getApQueue_queriesApprovedAndApprovedForPayment() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(expenseReportRepository.findByReportStatusAndPaymentRoutingStatus(
                ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.getApQueue(pageable);

        verify(expenseReportRepository).findByReportStatusAndPaymentRoutingStatus(
                ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, pageable);
    }

    @Test
    void getPaymentDetails_returnsDetails_whenApprovedForPayment() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseLineItemService.getAllForReport(reportId)).thenReturn(List.of());
        when(approvalWorkflowService.getApprovalStatus(reportId))
                .thenReturn(new ApprovalStatusResponse(2, "Finance", "Finance", 2, false, false));

        var details = service.getPaymentDetails(reportId);

        assertThat(details.report()).isNotNull();
        assertThat(details.lineItems()).isEmpty();
        assertThat(details.approvalStatus().currentLevelOrder()).isEqualTo(2);
    }

    @Test
    void getPaymentDetails_returnsDetails_whenAlreadyPaymentCompleted() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseLineItemService.getAllForReport(reportId)).thenReturn(List.of());
        when(approvalWorkflowService.getApprovalStatus(reportId))
                .thenReturn(new ApprovalStatusResponse(2, "Finance", "Finance", 2, false, false));

        assertThat(service.getPaymentDetails(reportId)).isNotNull();
    }

    @Test
    void getPaymentDetails_throwsAccessDenied_whenNotYetApprovedForPayment() {
        ExpenseReport report = report(ReportStatus.PENDING_FINANCE_VERIFICATION, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.getPaymentDetails(reportId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getPaymentDetails_returnsDetails_whenHandoffFailed() {
        // AP_VISIBLE_STATUSES was widened alongside the new history tabs - a HANDOFF_FAILED report
        // must be viewable, not just APPROVED_FOR_PAYMENT/PAYMENT_COMPLETED.
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.HANDOFF_FAILED);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseLineItemService.getAllForReport(reportId)).thenReturn(List.of());
        when(approvalWorkflowService.getApprovalStatus(reportId))
                .thenReturn(new ApprovalStatusResponse(2, "Finance", "Finance", 2, false, false));

        assertThat(service.getPaymentDetails(reportId)).isNotNull();
    }

    // ---------------------------------------------------------------------
    // getApHistory
    // ---------------------------------------------------------------------

    @Test
    void getApHistory_queriesByTheGivenPaymentRoutingStatus() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(expenseReportRepository.findByPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.getApHistory(PaymentRoutingStatus.PAYMENT_COMPLETED, pageable);

        verify(expenseReportRepository).findByPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED, pageable);
    }

    @Test
    void getApHistory_mapsReportsToQueueItems() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED);
        when(expenseReportRepository.findByPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED, pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(report), pageable, 1));

        var page = service.getApHistory(PaymentRoutingStatus.PAYMENT_COMPLETED, pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).reportId()).isEqualTo(reportId);
        assertThat(page.content().get(0).paymentRoutingStatus()).isEqualTo("PAYMENT_COMPLETED");
    }

    @Test
    void getApHistory_throws_whenStatusIsApprovedForPayment() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getApHistory(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, pageable))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getApHistory_throws_whenStatusIsNone() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getApHistory(PaymentRoutingStatus.NONE, pageable))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getApHistory_throws_whenStatusIsNull() {
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.getApHistory(null, pageable))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSummary_countsPendingPaidAndFailed() {
        when(expenseReportRepository.countByReportStatusAndPaymentRoutingStatus(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT)).thenReturn(8L);
        when(expenseReportRepository.countByPaymentRoutingStatusAndPaymentCompletedAtGreaterThanEqual(eq(PaymentRoutingStatus.PAYMENT_COMPLETED), any())).thenReturn(3L);
        when(expenseReportRepository.countByPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED)).thenReturn(12L);
        when(expenseReportRepository.countByPaymentRoutingStatus(PaymentRoutingStatus.HANDOFF_FAILED)).thenReturn(1L);

        var summary = service.getSummary();

        assertThat(summary.pendingCount()).isEqualTo(8);
        assertThat(summary.paidThisMonthCount()).isEqualTo(3);
        assertThat(summary.paidCount()).isEqualTo(12);
        assertThat(summary.handoffFailedCount()).isEqualTo(1);
    }
}
