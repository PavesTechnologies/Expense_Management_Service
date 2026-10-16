package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLineItemReview;
import com.expense_management_service.entity.ApprovalSplitReview;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.BudgetEncumbrance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.FinanceVerificationReview;
import com.expense_management_service.entity.VerificationQuery;
import com.expense_management_service.repository.ApprovalLineItemReviewRepository;
import com.expense_management_service.repository.ApprovalSplitReviewRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.FinanceVerificationReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets an employee remove a line from a report that has already been through review (during a
 * correction). Its review rows, split reviews and budget reservations reference it with no cascade,
 * so the delete would otherwise fail. What the reviewers decided - and any Finance query on it - is
 * written to the audit log first, so the report's history still shows the removed line.
 * The reservations are dropped rather than released: they were never consumed (a line can only be
 * removed before payment), and the resubmission re-reserves budget for the lines that remain.
 */
@Component
@RequiredArgsConstructor
public class SubmittedLineItemRemover {

    private final FinanceVerificationReviewRepository financeVerificationReviewRepository;
    private final ApprovalLineItemReviewRepository approvalLineItemReviewRepository;
    private final ApprovalSplitReviewRepository approvalSplitReviewRepository;
    private final BudgetEncumbranceRepository budgetEncumbranceRepository;
    private final AuditLogRepository auditLogRepository;

    public void prepareForDeletion(ExpenseLineItem line, String actingEmployeeId) {
        List<FinanceVerificationReview> financeReviews = financeVerificationReviewRepository.findByLineItem_LineItemId(line.getLineItemId());
        List<ApprovalLineItemReview> approvalReviews = approvalLineItemReviewRepository.findByLineItem_LineItemId(line.getLineItemId());
        List<ApprovalSplitReview> splitReviews = approvalSplitReviewRepository.findBySplit_LineItem_LineItemId(line.getLineItemId());
        List<BudgetEncumbrance> reservations = budgetEncumbranceRepository.findBySplit_LineItem_LineItemId(line.getLineItemId());

        auditLogRepository.save(AuditLog.builder()
                .entityName("ExpenseLineItem")
                .entityId(line.getLineItemId())
                .action("LINE_ITEM_REMOVED_DURING_CORRECTION")
                .oldValue(describe(line, financeReviews, approvalReviews, line.getVerificationQueries()))
                .newValue("REMOVED")
                .reason("Removed by the employee while correcting report "
                        + (line.getReport() != null ? line.getReport().getReportNumber() : ""))
                .performedBy(actingEmployeeId)
                .performedAt(LocalDateTime.now())
                .build());

        approvalSplitReviewRepository.deleteAll(splitReviews);
        budgetEncumbranceRepository.deleteAll(reservations);
        financeVerificationReviewRepository.deleteAll(financeReviews);
        approvalLineItemReviewRepository.deleteAll(approvalReviews);
    }

    private static String describe(ExpenseLineItem line, List<FinanceVerificationReview> financeReviews,
                                   List<ApprovalLineItemReview> approvalReviews, List<VerificationQuery> queries) {
        List<String> parts = new ArrayList<>();
        parts.add("merchant=" + line.getMerchantName() + " date=" + line.getExpenseDate() + " amount=" + line.getAmount()
                + (line.getCurrency() != null ? " " + line.getCurrency().getCurrencyCode() : ""));
        approvalReviews.forEach(r -> parts.add("approval:" + r.getStatus() + " by=" + r.getActedBy() + " at=" + r.getActionedAt()
                + (r.getComment() != null ? " comment=" + r.getComment() : "")));
        financeReviews.forEach(r -> parts.add("finance:" + r.getStatus() + " by=" + r.getActedBy() + " at=" + r.getActionedAt()
                + (r.getComment() != null ? " comment=" + r.getComment() : "")));
        if (queries != null) {
            queries.forEach(q -> parts.add("query:" + q.getStatus() + " text=" + q.getQueryText()));
        }
        return String.join(" | ", parts);
    }
}
