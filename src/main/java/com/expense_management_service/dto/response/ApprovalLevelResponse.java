package com.expense_management_service.dto.response;

import com.expense_management_service.enums.LevelQuorum;

import java.util.List;
import java.util.UUID;

/** {@code levelName} is the raw stored value (nullable); {@code displayName} is never null - falls back to "Level " + levelOrder. */
public record ApprovalLevelResponse(
        UUID levelId,
        Integer levelOrder,
        String levelName,
        String displayName,
        LevelQuorum quorum,
        List<ApprovalLevelApproverResponse> approvers
) {
}
