package com.expense_management_service.integration.pms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Response from {@code GET /pms/api/my-work?userId=...} — PMS's {@code MyWorkResponseDto}.
 * Verified against PMS source directly: this is a work-items dashboard aggregate (tasks,
 * stories, bugs grouped by project), NOT a project catalog — {@code projects} carries only
 * {@code projectId}/{@code projectName} per group, nothing else (no key, status, client id,
 * dates). PMS's own {@code MyWorkService.getMyWork} already restricts the {@code projects} list
 * to projects whose status is ACTIVE or PLANNING, so EMS does not need to re-filter by status.
 * <p>
 * Full project detail (including {@code clientId}, required for RMS client resolution) still
 * comes from {@code GET /api/projects/{id}} — see {@link PmsClient#getProject}. This response is
 * only ever used to populate the employee's project-picker list.
 * <p>
 * Only the fields EMS actually uses are declared; {@code @JsonIgnoreProperties(ignoreUnknown)}
 * tolerates the rest of PMS's much larger payload (counts, testWork, PROJECT_MANAGERItems, etc.).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PmsMyWorkResponse(List<ProjectWorkGroup> projects) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectWorkGroup(Long projectId, String projectName) {
    }
}
