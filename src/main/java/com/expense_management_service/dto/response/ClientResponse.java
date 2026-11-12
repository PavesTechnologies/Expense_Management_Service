package com.expense_management_service.dto.response;

import java.util.UUID;

/**
 * One active client, as owned by RMS. {@code clientId} is RMS's own client UUID — the same value
 * PMS stores on a project and XMS stores on {@code ProjectCache.clientId}.
 */
public record ClientResponse(
        UUID clientId,
        String clientName
) {
}
