package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record IntegrationSyncResponse(
        UUID integrationId,
        String integrationName,
        UUID referenceId,
        String requestPayload,
        String responsePayload,
        String syncStatus,
        Integer retryCount,
        LocalDateTime lastSyncedAt
) {
}
