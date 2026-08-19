package com.expense_management_service.service;

import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.LineItemReviewResponse;
import com.expense_management_service.dto.response.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * The new Approval Flow Engine orchestrator (replaces EP06's matrix-based {@code ApprovalWorkflowService}).
 * Implements the full state machine (§6): DRAFT --submit--> PENDING_APPROVAL --line reviews--> APPROVED,
 * with AWAITING_CORRECTION as a non-terminal correction loop and REJECTED/CANCELLED as terminal exits.
 */
public interface ApprovalWorkflowService {

    /** Resolves the matching flow, materialises the chain, activates the first level. */
    ExpenseReportResponse submit(UUID reportId);

    /** Employee-initiated: PENDING_APPROVAL/AWAITING_CORRECTION -> DRAFT. Blocked once any level has approved (§6). */
    ExpenseReportResponse recall(UUID reportId, String actingEmployeeId);

    /** Employee-initiated, terminal abandon. Blocked once any level has approved (§6) - same restriction as recall. */
    ExpenseReportResponse cancel(UUID reportId, String actingEmployeeId);

    /** Approve or flag one line item at the report's currently-active level (§4.7). */
    ExpenseReportResponse reviewLineItem(UUID reportId, UUID lineItemId, String actingEmployeeId, LineItemReviewRequest request);

    /** Whole-report, terminal Reject (§6) - distinct from line-level Needs Correction. */
    ExpenseReportResponse rejectReport(UUID reportId, String actingEmployeeId, RejectReportRequest request);

    /**
     * Presence-based "My Approvals" (§1.5/§9.1) - every report where the caller (or their active
     * delegate) currently has an ACTIVE assignment. Server-side paginated (§14) - resolved via a
     * single {@code approverId IN (...)} query rather than loading every ACTIVE assignment
     * system-wide into memory.
     */
    PageResponse<ApprovalQueueItemResponse> getMyQueue(String actingEmployeeId, Pageable pageable);

    /** Approves every line item on a report in one transaction - only for reports with zero pending flags (§4.4/§10.3). */
    ExpenseReportResponse bulkApprove(UUID reportId, String actingEmployeeId);

    /**
     * Current-submission-cycle line item review status + comment - lets the report owner see what
     * needs correcting and why, and lets any past/current assignee see full multi-level context.
     * Visible to the report owner or anyone who has ever been (or is a delegate of) an assignee on
     * this report; throws {@code AccessDeniedException} otherwise.
     */
    List<LineItemReviewResponse> getLineItemReviews(UUID reportId, String actingEmployeeId);

    /** The read model behind a meaningful status pill and Recall/Cancel button enablement. */
    ApprovalStatusResponse getApprovalStatus(UUID reportId);

    /**
     * Reports the caller has already decided on. {@code outcome} is {@code "APPROVED"},
     * {@code "REJECTED"}, or {@code null} for both. Approved = the caller has a COMPLETED
     * assignment on a report now APPROVED; Rejected = the caller is the report's {@code rejectedBy}.
     * Does not (yet) attribute delegate-acted decisions back to the delegate - a documented
     * simplification, not an oversight. Server-side paginated (§14) via a single query - see
     * {@code ExpenseReportRepository.findHistoryForApprover}.
     */
    PageResponse<ExpenseReportResponse> getMyHistory(String actingEmployeeId, String outcome, Pageable pageable);

    /**
     * Re-entry point for a level-type strategy other than APPROVAL (currently: Finance
     * Verification) whose own action just brought every line item at {@code instanceId} to that
     * level's terminal positive outcome. Runs the exact same SEQUENTIAL-advance / level-completion
     * / next-level-activation / report-completion logic {@code reviewLineItem} uses internally for
     * Manager approval - a no-op if the level is not actually complete yet (e.g. a SEQUENTIAL
     * level with another entry still pending its own pass).
     */
    void advanceAfterLevelReviewed(UUID reportId, UUID instanceId, String completingApproverId);
}
