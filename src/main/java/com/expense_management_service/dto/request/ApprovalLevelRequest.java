package com.expense_management_service.dto.request;

import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code levelName} is optional - blank/null falls back to "Level N" wherever displayed.
 * {@code levelType} is optional and defaults to {@code APPROVAL} when omitted ({@code
 * ApprovalFlowMapper} applies the default) - existing callers that don't send it keep getting
 * ordinary Manager-style levels, zero behavior change.
 */
public record ApprovalLevelRequest(
        @NotNull Integer levelOrder,
        @Size(max = 255) String levelName,
        @NotNull LevelQuorum quorum,
        LevelType levelType,
        @NotEmpty @Valid List<ApprovalLevelApproverRequest> approvers
) {
}
