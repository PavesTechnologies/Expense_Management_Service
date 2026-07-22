package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record GlAccountResponse(
        UUID glAccountId,
        String glAccountCode,
        String glAccountName,
        String accountType,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
