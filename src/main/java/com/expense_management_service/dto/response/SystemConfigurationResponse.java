package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record SystemConfigurationResponse(
        UUID configId,
        String configKey,
        String configValue,
        String dataType,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
