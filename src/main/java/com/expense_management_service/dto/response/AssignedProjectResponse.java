package com.expense_management_service.dto.response;

import java.util.UUID;

/**
 * One PMS-assigned project the authenticated employee can select for a client-billable
 * expense. {@code projectId} is the local {@code ProjectCache} UUID — the same value
 * {@code ExpenseLineItemRequest.projectId} expects — not PMS's own numeric id.
 */
public record AssignedProjectResponse(
        UUID projectId,
        /** PMS's projectKey (e.g. AP-297). */
        String projectCode,
        /** PMS's own numeric project id. */
        Long pmsProjectId,
        String projectName,
        String status,
        /**
         * RMS client UUID that owns this project in PMS — the same id {@code GET /xms/clients}
         * returns, so the frontend can list only the selected client's projects. Null if PMS
         * could not be asked for this project's details and none was cached before.
         */
        UUID clientId
) {
}
