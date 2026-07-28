package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

public record EscalationRunResponse(
        int overdueScanned,
        int escalated,
        int stalledNoTarget,
        LocalDateTime executedAt,
        String note
) {
}
