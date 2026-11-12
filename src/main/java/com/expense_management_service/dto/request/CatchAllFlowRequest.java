package com.expense_management_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Configures the singleton catch-all flow's levels. No name/priority/criteria - it always matches, always evaluates last. */
public record CatchAllFlowRequest(
        @NotEmpty @Valid List<ApprovalLevelRequest> levels
) {
}
