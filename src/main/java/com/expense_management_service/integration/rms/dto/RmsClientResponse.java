package com.expense_management_service.integration.rms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * The {@code data} payload of {@code GET /rms/api/client/{id}} (wrapped in {@link RmsApiResponse})
 * — RMS's {@code Client} entity, restricted to the fields relevant to EMS. Verified against a
 * live response: RMS serializes the name as snake_case {@code client_name} while {@code clientId}
 * and {@code status} are camelCase, hence the explicit {@link JsonProperty}. RMS has no billing
 * fields on Client at all (confirmed) — do not add any here without a confirmed RMS-side
 * source. Not-found returns HTTP 400 (not 404) — see {@code RmsClientImpl.getClient}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RmsClientResponse(
        UUID clientId,
        @JsonProperty("client_name") String clientName,
        String status
) {
}
