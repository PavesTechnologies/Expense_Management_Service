package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemConfigurationRequest(
        @NotBlank @Size(max = 255) String configKey,
        String configValue,
        @Size(max = 255) String dataType,
        String description
) {
}
