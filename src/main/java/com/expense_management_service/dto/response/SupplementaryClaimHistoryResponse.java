package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

public record SupplementaryClaimHistoryResponse(
        String actorId,
        String actorRole,
        String action,
        String previousStatus,
        String newStatus,
        String comment,
        LocalDateTime createdAt
) {
}
