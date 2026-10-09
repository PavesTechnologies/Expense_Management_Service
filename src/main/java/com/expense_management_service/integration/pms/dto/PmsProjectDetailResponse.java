package com.expense_management_service.integration.pms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response from {@code GET /pms/api/projects/{id}} — PMS's {@code ProjectDto}, restricted to
 * the fields confirmed in the PMS technical analysis and relevant to EMS. Not-found returns
 * HTTP 400 (not 404) per that analysis — see {@code PmsClientImpl.getProject}, which handles
 * this directly rather than relying on Spring's default 404 semantics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PmsProjectDetailResponse(
        Long id,
        String projectKey,
        String name,
        String description,
        String status,
        UUID clientId,
        Long ownerId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        LocalDateTime updatedAt
) {
}
