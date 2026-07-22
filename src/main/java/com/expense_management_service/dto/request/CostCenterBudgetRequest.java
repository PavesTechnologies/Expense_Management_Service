package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CostCenterBudgetRequest(
        @NotNull UUID costCenterId,
        @NotBlank @Size(max = 255) String fiscalYear,
        @NotNull BigDecimal budgetAmount,
        BigDecimal availableBudget
) {
}
