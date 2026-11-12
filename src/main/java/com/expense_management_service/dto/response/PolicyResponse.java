package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyResponse(
        UUID policyId,
        String policyName,
        String description,
        String status,
        int currentVersion,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
