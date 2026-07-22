package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record IntegrationSyncRequest(
        @NotBlank @Size(max = 255) String integrationName,
        UUID referenceId,
        String requestPayload,
        String responsePayload,
        @Size(max = 255) String syncStatus,
        @PositiveOrZero Integer retryCount
) {
}
