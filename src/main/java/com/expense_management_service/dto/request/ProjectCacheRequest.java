package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectCacheRequest(
        @NotBlank @Size(max = 255) String projectCode,
        @NotBlank @Size(max = 255) String projectName,
        @Size(max = 255) String clientName,
        @Size(max = 255) String status
) {
}
