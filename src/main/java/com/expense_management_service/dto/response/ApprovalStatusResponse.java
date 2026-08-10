package com.expense_management_service.dto.response;

/**
 * The read model behind a meaningful status pill (e.g. "Pending Manager Approval") and behind
 * disabling Recall/Cancel instead of letting them fail server-side. All fields are null/false when
 * not applicable (e.g. report is DRAFT - no current level, nothing to recall/cancel out of yet).
 */
public record ApprovalStatusResponse(
        Integer currentLevelOrder,
        String currentLevelName,
        String currentLevelDisplayName,
        Integer totalLevels,
        boolean canRecall,
        boolean canCancel
) {
}
