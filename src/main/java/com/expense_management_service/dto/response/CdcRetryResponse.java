package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

public record CdcRetryResponse(
        int attempted,
        int succeeded,
        int failed,
        LocalDateTime executedAt,
        String note
) {
}
