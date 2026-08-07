package com.expense_management_service.dto.request;

import com.expense_management_service.enums.LevelQuorum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ApprovalLevelRequest(
        @NotNull Integer levelOrder,
        @NotNull LevelQuorum quorum,
        @NotEmpty @Valid List<ApprovalLevelApproverRequest> approvers
) {
}
