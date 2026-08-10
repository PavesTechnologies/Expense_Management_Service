package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyGroupResponse(
        UUID groupId,
        String groupName,
        String description,
        String status,
        int memberCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
