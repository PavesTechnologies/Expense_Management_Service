package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

/** Reminders-only (§5.4) - there is no more escalated/stalled distinction; nothing here ever auto-reassigns. */
public record EscalationRunResponse(
        int overdueScanned,
        int remindersSent,
        LocalDateTime executedAt,
        String note
) {
}
