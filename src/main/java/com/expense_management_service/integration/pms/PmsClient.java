package com.expense_management_service.integration.pms;

import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;

import java.util.List;
import java.util.Optional;

/**
 * Read-only client for project data that PMS owns.
 * <p>
 * XMS never mutates PMS data through this client — it only reads a caller's assigned
 * projects and individual project details to support client-billable expense submission
 * (project selection, and re-validation of project/client association at submission time).
 * The bearer token used is the caller's own token, forwarded automatically by the
 * {@code pmsRestClient} interceptor (see {@code RestClientConfig}) — PMS accepts the same
 * {@code GENERAL} role XMS already issues to ordinary employees, so no service account is
 * needed here (contrast {@code RmsClient}, which does need one).
 */
public interface PmsClient {

    /**
     * Active/planning projects the given UMS numeric user id owns or is a member of, via {@code
     * GET /pms/api/my-work?userId=...} — verified against PMS source; PMS's actual
     * {@code /api/projects/member/{userId}/active-projects} does not exist. {@code /api/my-work}
     * is really a work-items dashboard endpoint, but its {@code projects} list is exactly the
     * project id/name pairs needed here, already restricted by PMS itself to ACTIVE/PLANNING
     * status — see {@code PmsMyWorkResponse}'s javadoc. {@code userId} is a required query
     * parameter on this PMS endpoint, not derived from the caller's JWT.
     */
    List<PmsProjectSummaryResponse> getMyActiveProjects(Long umsUserId);

    /**
     * A single project by PMS's own numeric id. {@code GET /pms/api/projects/{id}}.
     * Empty if PMS reports not-found (confirmed to return HTTP 400, not 404).
     */
    Optional<PmsProjectDetailResponse> getProject(Long pmsProjectId);
}
