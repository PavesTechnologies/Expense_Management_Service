package com.expense_management_service.dto.response;

import java.util.UUID;

/**
 * One PMS-assigned project the authenticated employee can select for a client-billable
 * expense. {@code projectId} is the local {@code ProjectCache} UUID — the same value
 * {@code ExpenseLineItemRequest.projectId} expects — not PMS's own numeric id.
 */
public record AssignedProjectResponse(
        UUID projectId,
        String projectCode,
        String projectName,
        String status
) {
}
