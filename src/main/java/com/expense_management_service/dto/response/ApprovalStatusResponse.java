package com.expense_management_service.dto.response;

import java.util.List;

/**
 * @param correctionRequestedBy while the report is back with the employee for correction: who sent
 *                              it back - {@code "APPROVER"} or {@code "FINANCE"}; null otherwise.
 *                              Both leave the report AWAITING_CORRECTION, so the status alone
 *                              can't tell the employee which one to answer.
 * @param correctionRequests    the line items sent back and why; empty when not in correction
 * @param reportStatus          the report's status as of this call, so a progress view built from
 *                              this response never pairs fresh level data with a stale status
 * @param levels                every level of the current submission cycle, in order, with who
 *                              approves it and who acted
 */
public record ApprovalStatusResponse(
        Integer currentLevelOrder,
        String currentLevelName,
        String currentLevelDisplayName,
        Integer totalLevels,
        boolean canRecall,
        boolean canCancel,
        String correctionRequestedBy,
        List<CorrectionRequestResponse> correctionRequests,
        String reportStatus,
        List<ApprovalLevelProgressResponse> levels
) {

    public ApprovalStatusResponse(Integer currentLevelOrder, String currentLevelName, String currentLevelDisplayName,
                                  Integer totalLevels, boolean canRecall, boolean canCancel) {
        this(currentLevelOrder, currentLevelName, currentLevelDisplayName, totalLevels, canRecall, canCancel,
                null, List.of(), null, List.of());
    }
}
