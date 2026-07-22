package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record SavedFilterResponse(
        UUID filterId,
        String employeeId,
        String filterName,
        String filterJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
