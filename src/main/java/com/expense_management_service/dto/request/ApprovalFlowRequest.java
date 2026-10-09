package com.expense_management_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creates/updates a regular (non-catch-all) flow - the singleton catch-all flow is managed
 * separately via {@code ApprovalFlowController.updateCatchAllFlow}. {@code criteriaPattern} must
 * only reference indices present in {@code criteria} (validated server-side, e.g. {@code "(1 AND 2) OR 3"}).
 */
public record ApprovalFlowRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull Integer priority,
        @NotBlank String criteriaPattern,
        @NotEmpty @Valid List<ApprovalFlowCriterionRequest> criteria,
        @NotEmpty @Valid List<ApprovalLevelRequest> levels,
        @Size(max = 255) String status
) {
}
