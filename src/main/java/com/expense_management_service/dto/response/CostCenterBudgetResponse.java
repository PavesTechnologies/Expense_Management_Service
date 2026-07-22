package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CostCenterBudgetResponse(
        UUID budgetId,
        UUID costCenterId,
        String costCenterName,
        String fiscalYear,
        BigDecimal budgetAmount,
        BigDecimal availableBudget,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
