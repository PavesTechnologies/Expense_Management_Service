package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProjectCacheResponse(
        UUID projectId,
        String projectCode,
        String projectName,
        String clientName,
        String status,
        LocalDateTime syncedAt
) {
}
