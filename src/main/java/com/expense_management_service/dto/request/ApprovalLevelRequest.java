package com.expense_management_service.dto.request;

import com.expense_management_service.enums.LevelQuorum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code levelName} is optional - blank/null falls back to "Level N" wherever displayed. */
public record ApprovalLevelRequest(
        @NotNull Integer levelOrder,
        @Size(max = 255) String levelName,
        @NotNull LevelQuorum quorum,
        @NotEmpty @Valid List<ApprovalLevelApproverRequest> approvers
) {
}
