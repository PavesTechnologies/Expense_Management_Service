package com.expense_management_service.integration.rms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * One element of the {@code data} list returned by {@code GET /rms/api/client/get-active-clients}
 * (wrapped in {@link RmsApiResponse}) — RMS's {@code ActiveClientsPMSDTO}. Verified against RMS
 * source: plain camelCase {@code clientId}/{@code clientName} (no {@code @JsonProperty}, no global
 * naming strategy), unlike the snake_case {@code client_name} on the {@code Client} entity that
 * {@code GET /api/client/{id}} returns — see {@link RmsClientResponse}. RMS already restricts this
 * list to {@code RecordStatus.ACTIVE} clients.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RmsActiveClientResponse(UUID clientId, String clientName) {
}
