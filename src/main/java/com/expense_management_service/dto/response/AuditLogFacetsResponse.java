package com.expense_management_service.dto.response;

import java.util.List;

/** Values the Audit Logs screen offers in its filter dropdowns: only what has actually been logged. */
public record AuditLogFacetsResponse(
        List<String> entityNames,
        List<String> actions,
        List<String> sources
) {
}
