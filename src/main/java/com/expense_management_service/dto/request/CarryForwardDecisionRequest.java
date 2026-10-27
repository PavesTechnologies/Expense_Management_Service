package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reason given for approving, rejecting or reversing a carry-forward. */
public record CarryForwardDecisionRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
