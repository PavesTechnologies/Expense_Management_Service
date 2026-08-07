package com.expense_management_service.dto.response;

import com.expense_management_service.enums.LevelQuorum;

import java.util.List;
import java.util.UUID;

public record ApprovalLevelResponse(
        UUID levelId,
        Integer levelOrder,
        LevelQuorum quorum,
        List<ApprovalLevelApproverResponse> approvers
) {
}
