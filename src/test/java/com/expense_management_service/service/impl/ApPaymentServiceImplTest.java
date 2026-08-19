package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ExpenseLineItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    private ApPaymentServiceImpl service;

    private final UUID reportId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var factory = new ExpenseReportResponseFactory(new com.expense_management_service.mapper.ExpenseReportMapper(), policyViolationRepository);
        service = new ApPaymentServiceImpl(expenseReportRepository, expenseLineItemService, approvalWorkflowService,
                approvalEventPublisher, auditLogRepository, factory);
        when(policyViolationRepository.findByLineItem_Report_ReportId(any())).thenReturn(List.of());
    }

    private ExpenseReport report(ReportStatus reportStatus, PaymentRoutingStatus paymentRoutingStatus) {
        return ExpenseReport.builder().reportId(reportId).employeeId("5100001")
                .reportStatus(reportStatus).paymentRoutingStatus(paymentRoutingStatus).build();
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - valid transition
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_succeeds_whenApprovedForPayment() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReportResponse response = service.markPaymentCompleted(reportId, "5100099");

        assertThat(response).isNotNull();
        assertThat(report.getPaymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.PAYMENT_COMPLETED);
        assertThat(report.getPaymentCompletedBy()).isEqualTo("5100099");
        assertThat(report.getPaymentCompletedAt()).isNotNull();
        verify(approvalEventPublisher).publish(eq("PAYMENT_COMPLETED"), eq(reportId), any());
    }

    @Test
    void markPaymentCompleted_writesAuditLogEntry() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPaymentCompleted(reportId, "5100099");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityName()).isEqualTo("ExpenseReport");
        assertThat(saved.getEntityId()).isEqualTo(reportId);
        assertThat(saved.getAction()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(saved.getOldValue()).isEqualTo("APPROVED_FOR_PAYMENT");
        assertThat(saved.getNewValue()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(saved.getPerformedBy()).isEqualTo("5100099");
        assertThat(saved.getPerformedAt()).isNotNull();
    }

    // ---------------------------------------------------------------------
    // markPaymentCompleted - invalid transitions (spec §13 AP-status tests)
    // ---------------------------------------------------------------------

    @Test
    void markPaymentCompleted_throws_whenAlreadyPaymentCompleted() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.PAYMENT_COMPLETED);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(expenseReportRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void markPaymentCompleted_throws_whenDraft() {
        ExpenseReport report = report(ReportStatus.DRAFT, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenPendingApproval() {
        ExpenseReport report = report(ReportStatus.PENDING_APPROVAL, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenPendingFinanceVerification() {
        ExpenseReport report = report(ReportStatus.PENDING_FINANCE_VERIFICATION, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenAwaitingCorrection() {
        ExpenseReport report = report(ReportStatus.AWAITING_CORRECTION, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenApprovedButPaymentRoutingStatusNone() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.NONE);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenInvoiceHandoffPending() {
        ExpenseReport report = report(ReportStatus.APPROVED, PaymentRoutingStatus.INVOICE_HANDOFF_PENDING);
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markPaymentCompleted_throws_whenReportNotFound() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPaymentCompleted(reportId, "5100099"))
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
}
