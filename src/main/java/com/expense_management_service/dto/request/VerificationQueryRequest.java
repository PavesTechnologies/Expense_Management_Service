package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record VerificationQueryRequest(
        @NotNull UUID lineItemId,
        @NotBlank @Size(max = 255) String raisedBy,
        @NotBlank String queryText,
        String employeeResponse,
        @Size(max = 255) String status
) {
}
