package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One level of a report's approval chain in the current submission cycle, for the Approval
 * Progress view.
 *
 * @param levelName  the flow's own name for the level ("Level 2" when unnamed)
 * @param roleLabel  who approves at this level, in plain words: "Reporting Manager", "Cost Center
 *                   Owner", "Department Head", "Finance Verification" ...
 * @param levelType  APPROVAL or FINANCE_VERIFICATION
 * @param status     QUEUED (not reached yet), ACTIVE, COMPLETED or CANCELLED
 * @param approverIds employee IDs assigned at this level (excluding superseded ones)
 * @param decidedBy  who acted last at this level (the delegate, if one acted); null if nobody has
 * @param decidedAt  when that was
 * @param decision   APPROVED, NEEDS_CORRECTION, VERIFIED, QUERIED or REJECTED; null if undecided
 */
public record ApprovalLevelProgressResponse(
        Integer levelOrder,
        String levelName,
        String roleLabel,
        String levelType,
        String status,
        List<String> approverIds,
        String decidedBy,
        LocalDateTime decidedAt,
        String decision
) {
}
