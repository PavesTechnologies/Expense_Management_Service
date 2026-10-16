package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

/**
 * One event in a report's history, across every submission cycle - submissions, approvals,
 * correction requests (with the reviewer's comment), resubmissions, Finance verification, payment.
 *
 * @param action  workflow event type, e.g. REPORT_SUBMITTED, VERIFICATION_QUERY_RAISED, REPORT_RESUMED
 * @param comment the reviewer's comment for a correction request or rejection; null otherwise
 */
public record ReportHistoryEntryResponse(
        String action,
        String performedBy,
        LocalDateTime performedAt,
        String comment
) {
}
