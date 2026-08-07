package com.expense_management_service.service;

import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;

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

    /** Presence-based "My Approvals" (§1.5/§9.1) - every report where the caller (or their active delegate) currently has an ACTIVE assignment. */
    List<ApprovalQueueItemResponse> getMyQueue(String actingEmployeeId);

    /** Approves every line item on a report in one transaction - only for reports with zero pending flags (§4.4/§10.3). */
    ExpenseReportResponse bulkApprove(UUID reportId, String actingEmployeeId);
}
