package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse.CategoryUtilization;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse.LineUtilization;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse.LinkedReport;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse.ReceiptLink;
import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.enums.LineItemReviewStatus;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single place that turns a cash advance's linked reports into utilization figures. Settlement and
 * approval screens display this output; no caller should re-derive these numbers.
 * <p>
 * Callers are responsible for the access check. This class only calculates.
 */
@Component
@RequiredArgsConstructor
public class CashAdvanceUtilizationCalculator {

    private static final String CLOSED = "CLOSED";
    private static final String UNCATEGORIZED = "UNCATEGORIZED";

    private static final String VERIFIED_APPROVED = "APPROVED";
    private static final String VERIFIED_RETURNED = "RETURNED";
    private static final String VERIFIED_PENDING = "PENDING_REVIEW";
    private static final String VERIFIED_NOT_REVIEWED = "NOT_REVIEWED";

    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ReceiptRepository receiptRepository;
    private final ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    private final BudgetEncumbranceRepository budgetEncumbranceRepository;
    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceRepaymentRepository cashAdvanceRepaymentRepository;

    public CashAdvanceUtilizationResponse calculate(CashAdvance advance) {
        UUID advanceId = advance.getAdvanceId();

        List<ExpenseReport> reports = expenseReportRepository.findByCashAdvance_AdvanceIdOrderByCreatedAtAsc(advanceId);

        Map<UUID, BigDecimal> reservedByReport = reservedAmountByReport(advanceId);

        List<LinkedReport> linkedReports = new ArrayList<>();
        Map<String, CategoryAccumulator> categories = new LinkedHashMap<>();

        BigDecimal claimed = BigDecimal.ZERO;
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;

        for (ExpenseReport report : reports) {
            UUID reportId = report.getReportId();
            List<ExpenseLineItem> lines = expenseLineItemRepository.findByReport_ReportId(reportId);
            Map<UUID, List<Receipt>> receiptsByLine = receiptsByLine(reportId);
            Map<UUID, List<ApprovalLineItemReview>> reviewsByLine = reviewsByLine(reportId);

            BigDecimal reportClaimed = BigDecimal.ZERO;

            for (ExpenseLineItem line : lines) {
                BigDecimal amount = lineAmount(line);
                List<Receipt> receipts = receiptsByLine.getOrDefault(line.getLineItemId(), List.of());
                String verification = verificationStatus(reviewsByLine.getOrDefault(line.getLineItemId(), List.of()));

                reportClaimed = reportClaimed.add(amount);
                claimed = claimed.add(amount);
                switch (verification) {
                    case VERIFIED_APPROVED -> approved = approved.add(amount);
                    case VERIFIED_RETURNED -> returned = returned.add(amount);
                    default -> pending = pending.add(amount);
                }

                CategoryAccumulator category = categories.computeIfAbsent(
                        categoryKey(line.getCategory()), k -> new CategoryAccumulator(line.getCategory()));
                category.add(line, amount, verification, receipts);
            }

            linkedReports.add(new LinkedReport(
                    reportId,
                    report.getReportNumber(),
                    report.getReportStatus() != null ? report.getReportStatus().name() : null,
                    reportClaimed,
                    reservedByReport.getOrDefault(reportId, BigDecimal.ZERO)));
        }

        List<CashAdvanceAdjustment> adjustments = cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advanceId);
        List<CashAdvanceRepayment> repayments = cashAdvanceRepaymentRepository.findByCashAdvance_AdvanceId(advanceId);

        BigDecimal approvedUtilization = sum(adjustments.stream().map(CashAdvanceAdjustment::getAdjustedAmount));
        BigDecimal totalRepaid = sum(repayments.stream().map(CashAdvanceRepayment::getAmount));
        BigDecimal submittedReservations = reservedByReport.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal advanceAmount = nz(advance.getAmount());
        BigDecimal outstanding = nz(advance.getOutstandingBalance());
        boolean balanceConsistent = advanceAmount.subtract(approvedUtilization).subtract(totalRepaid)
                .compareTo(outstanding) == 0;

        boolean closed = CLOSED.equalsIgnoreCase(advance.getStatus());

        return new CashAdvanceUtilizationResponse(
                advanceId,
                advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : null,
                advanceAmount,
                claimed,
                approved,
                returned,
                pending,
                submittedReservations,
                approvedUtilization,
                totalRepaid,
                repayments.size(),
                outstanding,
                balanceConsistent,
                advance.getStatus(),
                closureStatus(closed, outstanding),
                closed,
                linkedReports,
                categories.values().stream().map(CategoryAccumulator::toResponse).toList());
    }

    // ------------------------------------------------------------ helpers

    private Map<UUID, BigDecimal> reservedAmountByReport(UUID advanceId) {
        Map<UUID, BigDecimal> byReport = new HashMap<>();
        List<BudgetEncumbrance> active = budgetEncumbranceRepository
                .findByReport_CashAdvance_AdvanceIdAndStatus(advanceId, BudgetEncumbranceStatus.ACTIVE);
        for (BudgetEncumbrance encumbrance : active) {
            byReport.merge(encumbrance.getReport().getReportId(), nz(encumbrance.getAmount()), BigDecimal::add);
        }
        return byReport;
    }

    private Map<UUID, List<Receipt>> receiptsByLine(UUID reportId) {
        Map<UUID, List<Receipt>> byLine = new HashMap<>();
        for (Receipt receipt : receiptRepository.findByReport_ReportId(reportId)) {
            if (receipt.getLineItem() != null) {
                byLine.computeIfAbsent(receipt.getLineItem().getLineItemId(), k -> new ArrayList<>()).add(receipt);
            }
        }
        return byLine;
    }

    private Map<UUID, List<ApprovalLineItemReview>> reviewsByLine(UUID reportId) {
        Map<UUID, List<ApprovalLineItemReview>> byLine = new HashMap<>();
        for (ApprovalLineItemReview review : approvalLineItemReviewRepository.findByLineItem_Report_ReportId(reportId)) {
            byLine.computeIfAbsent(review.getLineItem().getLineItemId(), k -> new ArrayList<>()).add(review);
        }
        return byLine;
    }

    /**
     * A line is APPROVED only when it has a review and every level has approved it. Any NEEDS_CORRECTION makes
     * it RETURNED. Anything else (including lines with no review yet) is still pending.
     */
    static String verificationStatus(List<ApprovalLineItemReview> reviews) {
        if (reviews.isEmpty()) {
            return VERIFIED_NOT_REVIEWED;
        }
        if (reviews.stream().anyMatch(r -> r.getStatus() == LineItemReviewStatus.NEEDS_CORRECTION)) {
            return VERIFIED_RETURNED;
        }
        if (reviews.stream().allMatch(r -> r.getStatus() == LineItemReviewStatus.APPROVED)) {
            return VERIFIED_APPROVED;
        }
        return VERIFIED_PENDING;
    }

    /** Base-currency amount, falling back to the claimed amount when the base figure has not been stored. */
    static BigDecimal lineAmount(ExpenseLineItem line) {
        return nz(line.getBaseAmount() != null ? line.getBaseAmount() : line.getAmount());
    }

    static String closureStatus(boolean closed, BigDecimal outstanding) {
        if (closed) {
            return CLOSED;
        }
        int sign = outstanding.signum();
        if (sign > 0) {
            return "REPAYMENT_DUE";
        }
        if (sign < 0) {
            return "REIMBURSEMENT_DUE";
        }
        return "PENDING_CLOSURE";
    }

    private static String categoryKey(ExpenseCategory category) {
        return category != null && category.getCategoryId() != null
                ? category.getCategoryId().toString()
                : UNCATEGORIZED;
    }

    private static BigDecimal sum(java.util.stream.Stream<BigDecimal> values) {
        return values.filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** Per-category running totals plus its line detail. */
    private static final class CategoryAccumulator {

        private final ExpenseCategory category;
        private final List<LineUtilization> lines = new ArrayList<>();
        private BigDecimal claimed = BigDecimal.ZERO;
        private BigDecimal approved = BigDecimal.ZERO;
        private BigDecimal returned = BigDecimal.ZERO;
        private BigDecimal pending = BigDecimal.ZERO;
        private int missingReceipts;

        CategoryAccumulator(ExpenseCategory category) {
            this.category = category;
        }

        void add(ExpenseLineItem line, BigDecimal amount, String verification, List<Receipt> receipts) {
            claimed = claimed.add(amount);
            switch (verification) {
                case VERIFIED_APPROVED -> approved = approved.add(amount);
                case VERIFIED_RETURNED -> returned = returned.add(amount);
                default -> pending = pending.add(amount);
            }
            if (isReceiptRequired() && receipts.isEmpty()) {
                missingReceipts++;
            }
            List<ReceiptLink> links = receipts.stream()
                    .sorted(Comparator.comparing(Receipt::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(r -> new ReceiptLink(r.getReceiptId(), r.getOriginalFileName(), r.getContentType()))
                    .toList();
            lines.add(new LineUtilization(
                    line.getLineItemId(),
                    line.getReport() != null ? line.getReport().getReportId() : null,
                    line.getExpenseDate(),
                    line.getMerchantName(),
                    line.getDescription(),
                    amount,
                    verification,
                    links));
        }

        CategoryUtilization toResponse() {
            return new CategoryUtilization(
                    category != null ? category.getCategoryId() : null,
                    category != null ? category.getCategoryCode() : null,
                    category != null ? category.getCategoryName() : "Uncategorized",
                    isReceiptRequired(),
                    lines.size(),
                    missingReceipts,
                    claimed,
                    approved,
                    returned,
                    pending,
                    List.copyOf(lines));
        }

        private boolean isReceiptRequired() {
            return category != null && Boolean.TRUE.equals(category.getReceiptRequired());
        }
    }
}
