package com.expense_management_service.service.impl;
import com.expense_management_service.entity.ApPaymentBatch;
import com.expense_management_service.entity.ApPaymentAllocation;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CompletePaymentRequest;
import com.expense_management_service.dto.response.ApPaymentDetailsResponse;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
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
    private final CashAdvanceBalanceLedger cashAdvanceBalanceLedger;
    private final CashAdvanceRepository cashAdvanceRepository;
    private final com.expense_management_service.repository.ApPaymentBatchRepository apPaymentBatchRepository;
    private final com.expense_management_service.repository.ApPaymentAllocationRepository apPaymentAllocationRepository;

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
        // Row lock: two confirmations of the same payment run one after another. The second sees PAYMENT_COMPLETED,
        // is refused with a clear message, and consumes no budget and records no second payment.
        ExpenseReport report = expenseReportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
        PaymentRoutingStatus previous = report.getPaymentRoutingStatus();
        if (previous != PaymentRoutingStatus.APPROVED_FOR_PAYMENT) {
            throw new IllegalArgumentException(
                    "Report " + reportId + " cannot be marked as paid - it is not APPROVED_FOR_PAYMENT "
                            + "(reportStatus=" + report.getReportStatus() + ", paymentRoutingStatus=" + previous + ")");
        }
        // Partial batch allocations are paid against this report: the single-payment path would pay the full amount again.
        if (report.getPaidAmount() != null && report.getPaidAmount().signum() > 0) {
            throw new BusinessRuleViolationException("Report " + report.getReportNumber() + " has already been partly paid ("
                    + report.getPaidAmount().toPlainString() + " through batch payments). Pay the remainder through a batch payment.");
        }
        // One bank reference stands for one transaction: refuse a reference already recorded on a batch or a report.
        String checkedReference = request.paymentReference().trim();
        if (apPaymentBatchRepository.existsByBankReference(checkedReference) || expenseReportRepository.existsByPaymentReference(checkedReference)) {
            throw new BusinessRuleViolationException("Payment reference " + checkedReference
                    + " is already recorded. Each payment needs its own reference.");
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
        recheckLinkedCashAdvance(report, actingEmployeeId);

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
     * A paid reimbursement can be the last thing holding its cash advance open: once it is recorded, the advance closes
     * if its balance is zero and no other linked reimbursement is still owed.
     */
    private void recheckLinkedCashAdvance(ExpenseReport report, String actingEmployeeId) {
        CashAdvance advance = report.getCashAdvance();
        if (advance == null) {
            return;
        }
        cashAdvanceBalanceLedger.refreshStatus(advance, actingEmployeeId,
                "Reimbursement paid for report " + report.getReportNumber());
        cashAdvanceRepository.save(advance);
    }

    /**
     * Records one bank payment covering several reports (Decision 3). Every report is locked in a fixed order first, all
     * checks run before anything is written, and the batch, its allocations and the report changes commit together.
     *
     * <p>A report is marked PAYMENT_COMPLETED only by the allocation that settles its remaining payable amount. Earlier
     * partial allocations leave the report payable, and budget is consumed once, at that final allocation.
     */
    @Override
    public com.expense_management_service.dto.response.ApPaymentBatchResponse recordBatchPayment(
            com.expense_management_service.dto.request.ApPaymentBatchRequest request, String actingEmployeeId) {
        if (request == null || request.allocations() == null || request.allocations().isEmpty()) {
            throw new BusinessRuleViolationException("A batch needs at least one report allocation.");
        }
        String reference = request.bankReference() == null ? "" : request.bankReference().trim();
        if (reference.length() < 6) {
            throw new BusinessRuleViolationException("Enter the bank reference for the batch (at least 6 characters).");
        }
        if (request.paymentMethod() == null) {
            throw new BusinessRuleViolationException("Choose the payment method for the batch.");
        }
        if (request.paymentDate() == null || request.paymentDate().isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("The batch payment date cannot be in the future.");
        }
        BigDecimal total = request.totalAmount();
        if (total == null || total.signum() <= 0) {
            throw new BusinessRuleViolationException("The batch total must be greater than zero.");
        }
        Set<UUID> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (var allocation : request.allocations()) {
            if (allocation.reportId() == null) {
                throw new BusinessRuleViolationException("Every allocation needs a report.");
            }
            if (!seen.add(allocation.reportId())) {
                throw new BusinessRuleViolationException("Report " + allocation.reportId() + " is listed twice in this batch.");
            }
            if (allocation.amount() == null || allocation.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Each allocation must be greater than zero.");
            }
            sum = sum.add(allocation.amount());
        }
        if (sum.compareTo(total) != 0) {
            throw new BusinessRuleViolationException("The batch total " + total.toPlainString()
                    + " does not equal the sum of the allocations " + sum.toPlainString() + ".");
        }
        // One bank reference stands for one batch only. A reference already used by a batch or by a single payment is refused.
        if (apPaymentBatchRepository.existsByBankReference(reference) || expenseReportRepository.existsByPaymentReference(reference)) {
            throw new BusinessRuleViolationException("Bank reference " + reference
                    + " is already recorded. Each bank transaction needs its own reference.");
        }

        // Lock every report in a fixed order (by id), so two concurrent batches cannot deadlock on each other.
        Map<UUID, ExpenseReport> locked = new HashMap<>();
        request.allocations().stream()
                .map(a -> a.reportId())
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .forEach(id -> locked.put(id, expenseReportRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + id))));

        // Validate everything before anything is written.
        Map<UUID, BigDecimal> payableBefore = new HashMap<>();
        for (var allocation : request.allocations()) {
            ExpenseReport report = locked.get(allocation.reportId());
            if (report.getPaymentRoutingStatus() != PaymentRoutingStatus.APPROVED_FOR_PAYMENT) {
                throw new BusinessRuleViolationException("Report " + report.getReportNumber()
                        + " is not waiting for payment (payment status " + report.getPaymentRoutingStatus() + ").");
            }
            if (actingEmployeeId != null && actingEmployeeId.equals(report.getEmployeeId())) {
                throw new AccessDeniedException("You can't confirm payment of your own expense report");
            }
            BigDecimal remaining = amountOwed(report).subtract(report.getPaidAmount() != null ? report.getPaidAmount() : BigDecimal.ZERO);
            if (remaining.signum() <= 0) {
                throw new BusinessRuleViolationException("Report " + report.getReportNumber() + " has nothing left to pay.");
            }
            if (allocation.amount().compareTo(remaining) > 0) {
                throw new BusinessRuleViolationException("The allocation " + allocation.amount().toPlainString()
                        + " is more than the " + remaining.toPlainString() + " still payable on report " + report.getReportNumber() + ".");
            }
            payableBefore.put(allocation.reportId(), remaining);
        }

        LocalDateTime now = LocalDateTime.now();
        ApPaymentBatch batch = apPaymentBatchRepository.save(ApPaymentBatch.builder()
                .bankReference(reference)
                .paymentMethod(request.paymentMethod())
                .paymentDate(request.paymentDate())
                .totalAmount(total)
                .status(ApPaymentBatch.RECORDED)
                .remarks(request.remarks() != null && !request.remarks().isBlank() ? request.remarks().trim() : null)
                .recordedBy(actingEmployeeId)
                .recordedAt(now)
                .build());

        List<com.expense_management_service.dto.response.ApPaymentBatchResponse.AllocationResult> results = new ArrayList<>();
        for (var allocation : request.allocations()) {
            ExpenseReport report = locked.get(allocation.reportId());
            BigDecimal before = payableBefore.get(allocation.reportId());
            BigDecimal after = before.subtract(allocation.amount());
            boolean paidNow = after.signum() == 0;
            report.setPaidAmount((report.getPaidAmount() != null ? report.getPaidAmount() : BigDecimal.ZERO).add(allocation.amount()));
            if (paidNow) {
                // The final allocation settles the report: budget is consumed once here, as for a single payment.
                consumeBudgetAtPayment(report);
                report.setPaymentRoutingStatus(PaymentRoutingStatus.PAYMENT_COMPLETED);
                report.setPaymentCompletedBy(actingEmployeeId);
                report.setPaymentCompletedAt(now);
                report.setPaymentReference(reference);
                report.setPaymentMethod(request.paymentMethod());
                report.setPaymentDate(request.paymentDate());
                report.setPaymentRemarks(batch.getRemarks());
            }
            expenseReportRepository.save(report);
            apPaymentAllocationRepository.save(ApPaymentAllocation.builder()
                    .batch(batch)
                    .report(report)
                    .amount(allocation.amount())
                    .payableBefore(before)
                    .payableAfter(after)
                    .build());
            auditLogRepository.save(AuditLog.builder()
                    .entityName("ExpenseReport")
                    .entityId(report.getReportId())
                    .action("AP_BATCH_PAYMENT_ALLOCATED")
                    .oldValue("Payable: " + before.toPlainString())
                    .newValue("Allocated: " + allocation.amount().toPlainString() + ", Payable: " + after.toPlainString()
                            + (paidNow ? ", PAYMENT_COMPLETED" : ""))
                    .reason("Batch " + batch.getBatchId() + " reference " + reference)
                    .performedBy(actingEmployeeId)
                    .performedAt(now)
                    .build());
            results.add(new com.expense_management_service.dto.response.ApPaymentBatchResponse.AllocationResult(
                    report.getReportId(), allocation.amount(), before, after, paidNow));
        }
        auditLogRepository.save(AuditLog.builder()
                .entityName("ApPaymentBatch")
                .entityId(batch.getBatchId())
                .action("AP_BATCH_RECORDED")
                .oldValue(null)
                .newValue("Reference " + reference + ", total " + total.toPlainString() + ", " + results.size() + " report(s)")
                .performedBy(actingEmployeeId)
                .performedAt(now)
                .build());
        approvalEventPublisher.publish("AP_BATCH_RECORDED", batch.getBatchId(), "by=" + actingEmployeeId + " reference=" + reference);
        return new com.expense_management_service.dto.response.ApPaymentBatchResponse(
                batch.getBatchId(), reference, total, ApPaymentBatch.RECORDED, results);
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
     * {@code consumeBudget} ONLY when the report has zero encumbrances at all (an in-flight report that
     * reached APPROVED_FOR_PAYMENT before this wiring existed) - the two paths are mutually exclusive, so
     * budget is still never consumed twice. Both direct paths follow the report's splits.
     */
    private void consumeBudgetAtPayment(ExpenseReport report) {
        if (report.getCashAdvance() != null) {
            // Linked to a cash advance: offsets already moved their share of the advance into utilization.
            // Charge only what AP pays now, and release the reservation made for the full total.
            int linkedCycle = approvalWorkflowService.getCurrentSubmissionCycle(report.getReportId());
            budgetEncumbranceService.releaseActiveForCycle(report.getReportId(), linkedCycle);
            consumeAcrossCostCenters(report, report.getReimbursableAmount());
            return;
        }
        int cycle = approvalWorkflowService.getCurrentSubmissionCycle(report.getReportId());
        boolean consumedViaEncumbrances = budgetEncumbranceService.consumeActiveForReport(report.getReportId(), cycle);
        if (!consumedViaEncumbrances) {
            consumeAcrossCostCenters(report, report.getTotalAmount());
        }
    }

    /** Charges {@code amount} to each cost center the report's lines and splits allocate it to. */
    private void consumeAcrossCostCenters(ExpenseReport report, BigDecimal amount) {
        for (ReportCostCenterShares.Share share : ReportCostCenterShares.distribute(amount, report)) {
            costCenterBudgetService.consumeBudget(share.costCenter(), report.getFiscalYear(), share.amount());
        }
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }
}
