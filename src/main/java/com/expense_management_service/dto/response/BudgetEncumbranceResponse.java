package com.expense_management_service.dto.response;

import com.expense_management_service.enums.BudgetEncumbranceStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BudgetEncumbranceResponse(
        UUID encumbranceId,
        UUID reportId,
        UUID splitId,
        UUID costCenterId,
        String costCenterName,
        Integer submissionCycle,
        BigDecimal amount,
        BudgetEncumbranceStatus status,
        Boolean unbudgeted,
        LocalDateTime createdAt,
        LocalDateTime releasedAt,
        LocalDateTime consumedAt
) {
}
