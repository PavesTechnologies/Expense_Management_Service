package com.expense_management_service.integration.pms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * EMS's own internal shape for one project available for the employee to pick from — not a
 * direct 1:1 mapping of any single PMS DTO. {@code PmsClientImpl.getMyActiveProjects} populates
 * this from PMS's {@code GET /api/my-work} response, which only carries {@code projectId}/{@code
 * projectName} per project (verified against PMS source — see {@code PmsMyWorkResponse}'s
 * javadoc) — so {@code projectKey}, {@code description}, and {@code status} are always {@code
 * null} here by design, not a mapping gap. Full project detail (key, status, clientId, dates)
 * is fetched separately via {@code getProject} once a specific project is selected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PmsProjectSummaryResponse(
        Long id,
        String projectKey,
        String name,
        String description,
        String status
) {
}
