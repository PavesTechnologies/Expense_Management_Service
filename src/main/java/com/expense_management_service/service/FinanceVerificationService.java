package com.expense_management_service.service;

import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.FinanceLineItemReviewResponse;
import com.expense_management_service.dto.response.FinanceQueueItemResponse;
import com.expense_management_service.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Finance Verification's own action surface - deliberately separate from {@link
 * ApprovalWorkflowService} (rather than overloading {@code reviewLineItem}) because Finance
 * actions carry Finance-specific eligibility gating and audit snapshots {@code
 * ApprovalWorkflowService.reviewLineItem} never needs. Authorization is role+status based (§8):
 * any {@code FINANCE_EXECUTIVE} may act on any report currently at Finance Verification, not just
 * whichever approver a {@code FinanceTeamApprover} mapping happens to resolve for that report's
 * cost center - matching how the AP Payment queue/actions already work. Re-enters {@code
 * ApprovalWorkflowService.advanceAfterLevelReviewed} for level/report progression once every line
 * item is VERIFIED, so there is exactly one place that owns SEQUENTIAL/quorum/next-level logic.
 */
public interface FinanceVerificationService {

    /** Verifies one line item at the report's currently-active FINANCE_VERIFICATION level, after running eligibility checks. */
    ExpenseReportResponse verifyLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId);

    /**
     * Raises a query on one line item without rejecting the whole report (§Query vs Reject) - the
     * report moves to AWAITING_CORRECTION, every other already-VERIFIED line item at this instance
     * is left untouched, and the level cannot complete while this query is open.
     */
    ExpenseReportResponse queryLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId, String reason);

    /** Role+status based "My Finance Queue" (§8) - every report currently PENDING_FINANCE_VERIFICATION, visible to any FINANCE_EXECUTIVE regardless of per-report assignment. */
    PageResponse<FinanceQueueItemResponse> getFinanceQueue(String actingEmployeeId, Pageable pageable);

    /** Current-submission-cycle Finance review status + audit snapshot for every line item, across every FINANCE_VERIFICATION level of this cycle. */
    List<FinanceLineItemReviewResponse> getFinanceReviews(UUID reportId, String actingEmployeeId);
}
