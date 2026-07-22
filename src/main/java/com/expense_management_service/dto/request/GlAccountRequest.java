package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GlAccountRequest(
        @NotBlank @Size(max = 255) String glAccountCode,
        @NotBlank @Size(max = 255) String glAccountName,
        @Size(max = 255) String accountType,
        String description,
        @Size(max = 255) String status
) {
}
