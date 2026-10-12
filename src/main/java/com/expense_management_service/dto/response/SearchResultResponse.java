package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record SearchResultResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        String employeeName,
        String merchant,
        String category,
        String costCenter,
        String businessPurpose,
        String status,
        Double amount,
        LocalDateTime submittedAt,
        LocalDateTime createdAt
) {
}
