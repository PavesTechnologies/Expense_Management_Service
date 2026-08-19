package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ApPaymentDetailsResponse;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApPaymentService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ExpenseLineItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApPaymentServiceImpl implements ApPaymentService {

    /** {@code paymentRoutingStatus} values from which the report's payment details remain visible to AP. */
    private static final Set<PaymentRoutingStatus> AP_VISIBLE_STATUSES =
            Set.of(PaymentRoutingStatus.APPROVED_FOR_PAYMENT, PaymentRoutingStatus.PAYMENT_COMPLETED);

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemService expenseLineItemService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final AuditLogRepository auditLogRepository;
    private final ExpenseReportResponseFactory expenseReportResponseFactory;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApPaymentQueueItemResponse> getApQueue(Pageable pageable) {
        Page<ExpenseReport> page = expenseReportRepository
                .findByReportStatusAndPaymentRoutingStatus(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, pageable);
        return PageResponse.of(page.map(this::toQueueItem));
    }

    private ApPaymentQueueItemResponse toQueueItem(ExpenseReport report) {
        return new ApPaymentQueueItemResponse(
                report.getReportId(), report.getReportNumber(), report.getEmployeeId(), report.getTitle(),
                report.getTotalAmount(), report.getCurrency() != null ? report.getCurrency().getCurrencyCode() : null,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterId() : null,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterName() : null,
                report.getApprovedAt(), report.getReportStatus().name(), report.getPaymentRoutingStatus().name());
    }

    @Override
    @Transactional(readOnly = true)
    public ApPaymentDetailsResponse getPaymentDetails(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        if (!AP_VISIBLE_STATUSES.contains(report.getPaymentRoutingStatus())) {
            throw new AccessDeniedException(
                    "Report " + reportId + " has not reached Finance-approved-for-payment status and is not visible to AP");
        }
        return new ApPaymentDetailsResponse(
                expenseReportResponseFactory.toResponse(report),
                expenseLineItemService.getAllForReport(reportId),
                approvalWorkflowService.getApprovalStatus(reportId));
    }

    @Override
    public ExpenseReportResponse markPaymentCompleted(UUID reportId, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        PaymentRoutingStatus previous = report.getPaymentRoutingStatus();
        if (previous != PaymentRoutingStatus.APPROVED_FOR_PAYMENT) {
            throw new IllegalArgumentException(
                    "Report " + reportId + " cannot be marked as paid - it is not APPROVED_FOR_PAYMENT "
                            + "(reportStatus=" + report.getReportStatus() + ", paymentRoutingStatus=" + previous + ")");
        }

        LocalDateTime now = LocalDateTime.now();
        report.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        report.setPaymentCompletedBy(actingEmployeeId);
        report.setPaymentCompletedAt(now);
        expenseReportRepository.save(report);

        auditLogRepository.save(AuditLog.builder()
                .entityName("ExpenseReport")
                .entityId(reportId)
                .action("PAYMENT_COMPLETED")
                .oldValue(previous.name())
                .newValue(PaymentRoutingStatus.PAYMENT_COMPLETED.name())
                .performedBy(actingEmployeeId)
                .performedAt(now)
                .build());

        approvalEventPublisher.publish("PAYMENT_COMPLETED", reportId, "by=" + actingEmployeeId);

        log.info("Payment completed for report {} by {}", reportId, actingEmployeeId);
        return expenseReportResponseFactory.toResponse(findReport(reportId));
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }
}
