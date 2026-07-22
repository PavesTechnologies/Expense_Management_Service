package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SavedFilterRequest(
        @NotBlank @Size(max = 255) String employeeId,
        @NotBlank @Size(max = 255) String filterName,
        String filterJson
) {
}
