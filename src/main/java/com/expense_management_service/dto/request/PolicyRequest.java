package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PolicyRequest(
        @NotBlank @Size(max = 255) String policyName,
        @Size(max = 1000) String description,
        @Size(max = 255) String status
) {
}
