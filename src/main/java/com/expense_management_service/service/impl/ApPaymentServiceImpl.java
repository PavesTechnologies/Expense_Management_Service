package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.dto.response.ApPaymentDetailsResponse;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApPaymentService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.BudgetEncumbranceService;
import com.expense_management_service.service.CostCenterBudgetService;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.dto.response.ApPaymentSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApPaymentServiceImpl implements ApPaymentService {

    /**
     * {@code paymentRoutingStatus} values from which the report's payment details remain visible
     * to AP - every real routing outcome except {@code NONE} (a report with no Finance
     * Verification level at all never reaches AP in the first place).
     */
    private static final Set<PaymentRoutingStatus> AP_VISIBLE_STATUSES = Set.of(
            PaymentRoutingStatus.APPROVED_FOR_PAYMENT, PaymentRoutingStatus.PAYMENT_COMPLETED,
            PaymentRoutingStatus.INVOICE_HANDOFF_PENDING, PaymentRoutingStatus.INVOICE_HANDOFF_COMPLETED,
            PaymentRoutingStatus.HANDOFF_FAILED);

    /** Report totals are stored in the organization base currency (sum of line baseAmount), so they are labelled with it. */
    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemService expenseLineItemService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final AuditLogRepository auditLogRepository;
    private final ExpenseReportResponseFactory expenseReportResponseFactory;
    private final CostCenterBudgetService costCenterBudgetService;
    private final BudgetEncumbranceService budgetEncumbranceService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApPaymentQueueItemResponse> getApQueue(Pageable pageable) {
        Page<ExpenseReport> page = expenseReportRepository
                .findByReportStatusAndPaymentRoutingStatus(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, pageable);
        return PageResponse.of(page.map(this::toQueueItem));
    }

    @Override
    @Transactional(readOnly = true)
    public ApPaymentSummaryResponse getSummary() {
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        return new ApPaymentSummaryResponse(
                // Same filter as getApQueue, so the card always equals the Pending tab's count.
                expenseReportRepository.countByReportStatusAndPaymentRoutingStatus(ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT),
                expenseReportRepository.countByPaymentRoutingStatusAndPaymentCompletedAtGreaterThanEqual(PaymentRoutingStatus.PAYMENT_COMPLETED, startOfMonth),
                expenseReportRepository.countByPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED),
                expenseReportRepository.countByPaymentRoutingStatus(PaymentRoutingStatus.HANDOFF_FAILED));
    }

    private ApPaymentQueueItemResponse toQueueItem(ExpenseReport report) {
        return new ApPaymentQueueItemResponse(
                report.getReportId(), report.getReportNumber(), report.getEmployeeId(), report.getTitle(),
                report.getTotalAmount(), baseCurrencyCode,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterId() : null,
                report.getCostCenter() != null ? report.getCostCenter().getCostCenterName() : null,
                report.getApprovedAt(), report.getReportStatus().name(), report.getPaymentRoutingStatus().name(),
                report.getInvoiceHandoffStatus() != null ? report.getInvoiceHandoffStatus().name() : null,
                report.getPaymentCompletedAt(), report.getPaymentCompletedBy(),
                report.getTotalAmount(),
                ReimbursementCalculator.sum(report.getExpenseLineItems(), ExpenseLineItem::getBaseTaxAmount),
                ReimbursementCalculator.sum(report.getExpenseLineItems(), ExpenseLineItem::getBaseRecoverableTaxAmount),
                amountOwed(report),
                report.getPaymentReference(),
                report.getPaymentMethod() != null ? report.getPaymentMethod().name() : null,
                report.getPaymentDate(),
                report.getPaidAmount());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApPaymentQueueItemResponse> getApHistory(PaymentRoutingStatus status, Pageable pageable) {
        if (status == null || status == PaymentRoutingStatus.NONE || status == PaymentRoutingStatus.APPROVED_FOR_PAYMENT) {
            throw new IllegalArgumentException(
                    "AP Payment history does not support " + status + " - use getApQueue for the pending queue");
        }
        return PageResponse.of(expenseReportRepository.findByPaymentRoutingStatus(status, pageable).map(this::toQueueItem));
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
    public ExpenseReportResponse markPaymentCompleted(UUID reportId, CompletePaymentRequest request, String actingEmployeeId) {
        ExpenseReport report = findReport(reportId);
        PaymentRoutingStatus previous = report.getPaymentRoutingStatus();
        if (previous != PaymentRoutingStatus.APPROVED_FOR_PAYMENT) {
            throw new IllegalArgumentException(
                    "Report " + reportId + " cannot be marked as paid - it is not APPROVED_FOR_PAYMENT "
                            + "(reportStatus=" + report.getReportStatus() + ", paymentRoutingStatus=" + previous + ")");
        }
        assertPaymentMatchesReport(report, request, actingEmployeeId);

        // Budget is consumed exactly once, right here, at the moment payment actually completes -
        // not at submission, approval, Finance verification, or the earlier APPROVED_FOR_PAYMENT
        // routing decision. The guard above already makes this call unreachable on a duplicate
        // completion attempt (a second call sees previous == PAYMENT_COMPLETED and throws before
        // ever reaching this line), and this whole method runs in one @Transactional boundary, so a
        // failure here (e.g. a concurrent-update conflict on a budget row) rolls back the
        // paymentRoutingStatus/paymentCompletedBy/paymentCompletedAt writes below with it.
        consumeBudgetAtPayment(report);

        LocalDateTime now = LocalDateTime.now();
        String reference = request.paymentReference().trim();
        report.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
        report.setPaymentCompletedBy(actingEmployeeId);
        report.setPaymentCompletedAt(now);
        report.setPaymentReference(reference);
        report.setPaymentMethod(request.paymentMethod());
        report.setPaymentDate(request.paymentDate());
        report.setPaidAmount(request.amountPaid());
        report.setPaymentRemarks(request.remarks() != null && !request.remarks().isBlank() ? request.remarks().trim() : null);
        expenseReportRepository.save(report);

        auditLogRepository.save(AuditLog.builder()
                .entityName("ExpenseReport")
                .entityId(reportId)
                .action("PAYMENT_COMPLETED")
                .oldValue(previous.name())
                .newValue(PaymentRoutingStatus.PAYMENT_COMPLETED.name() + " reference=" + reference
                        + " method=" + request.paymentMethod() + " date=" + request.paymentDate()
                        + " amount=" + request.amountPaid().toPlainString() + " " + baseCurrencyCode)
                .reason(report.getPaymentRemarks())
                .performedBy(actingEmployeeId)
                .performedAt(now)
                .build());

        approvalEventPublisher.publish("PAYMENT_COMPLETED", reportId, "by=" + actingEmployeeId + " reference=" + reference);

        log.info("Payment completed for report {} by {} (reference {})", reportId, actingEmployeeId, reference);
        return expenseReportResponseFactory.toResponse(findReport(reportId));
    }

    /**
     * What AP confirms must be the payment this report is actually owed: not their own claim, a real
     * amount due, the exact amount (a mismatch usually means AP's screen predates a change Finance
     * made, e.g. a cash-advance offset), and a payment date no earlier than the approval.
     */
    private void assertPaymentMatchesReport(ExpenseReport report, CompletePaymentRequest request, String actingEmployeeId) {
        if (actingEmployeeId != null && actingEmployeeId.equals(report.getEmployeeId())) {
            throw new AccessDeniedException("You can't confirm payment of your own expense report");
        }
        BigDecimal owed = amountOwed(report);
        if (owed.signum() <= 0) {
            throw new BusinessRuleViolationException("Report " + report.getReportNumber()
                    + " has nothing to pay - it is fully covered by a cash advance");
        }
        if (request.amountPaid().subtract(owed).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new BusinessRuleViolationException("Amount paid " + request.amountPaid().toPlainString() + " " + baseCurrencyCode
                    + " doesn't match the " + owed.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + baseCurrencyCode
                    + " owed on report " + report.getReportNumber() + ". Refresh the report and check the amount before confirming.");
        }
        if (report.getApprovedAt() != null && request.paymentDate().isBefore(report.getApprovedAt().toLocalDate())) {
            throw new BusinessRuleViolationException("Payment date " + request.paymentDate()
                    + " is before the report was approved (" + report.getApprovedAt().toLocalDate() + ")");
        }
    }

    /** Net of cash-advance offsets - what the employee is actually owed. */
    private static BigDecimal amountOwed(ExpenseReport report) {
        return report.getReimbursableAmount() != null ? report.getReimbursableAmount()
                : ReimbursementCalculator.reimbursable(report.getTotalAmount(), report.getCashAdvanceAdjustments());
    }

    /**
     * Phase 6: consumes budget via whichever {@code BudgetEncumbrance} rows exist for the report's
     * current submission cycle - split-aware, per-Cost-Center consumption for any report submitted
     * after Phase 5's encumbrance wiring went live. Falls back to the original, pre-Phase-3 direct
     * whole-report {@code consumeBudget} call ONLY when the report has zero encumbrances at all (an
     * in-flight report that reached APPROVED_FOR_PAYMENT before this wiring existed) - the two paths
     * are mutually exclusive, so budget is still never consumed twice.
     */
    private void consumeBudgetAtPayment(ExpenseReport report) {
        if (report.getCashAdvance() != null) {
            // Linked to a cash advance: offsets already moved their share of the advance into utilization.
            // Charge only what AP pays now, and release the reservation made for the full total.
            int linkedCycle = approvalWorkflowService.getCurrentSubmissionCycle(report.getReportId());
            budgetEncumbranceService.releaseActiveForCycle(report.getReportId(), linkedCycle);
            java.math.BigDecimal payable = report.getReimbursableAmount() != null ? report.getReimbursableAmount() : java.math.BigDecimal.ZERO;
            if (payable.signum() > 0) {
                costCenterBudgetService.consumeBudget(report.getCostCenter(), report.getFiscalYear(), payable);
            }
            return;
        }
        int cycle = approvalWorkflowService.getCurrentSubmissionCycle(report.getReportId());
        boolean consumedViaEncumbrances = budgetEncumbranceService.consumeActiveForReport(report.getReportId(), cycle);
        if (!consumedViaEncumbrances) {
            costCenterBudgetService.consumeBudget(report.getCostCenter(), report.getFiscalYear(), report.getTotalAmount());
        }
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }
}
