package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code rolloverFromPrevious}, {@code allowRollover}, {@code rolloverCap}, and
 * {@code warningThreshold} are new (Phase 3 / Group 2 Lock). {@code rolloverSourceBudgetId} is
 * deliberately request-only, never persisted on {@code CostCenterBudget}: {@code fiscalYear} is
 * free text (this codebase's own existing tests use both "FY2026" and "2026" for the same concept),
 * so "the previous fiscal year's budget" cannot be safely auto-derived by string manipulation - the
 * admin explicitly names the specific source row a rollover figure was validated against instead.
 * Required only when {@code rolloverFromPrevious} is greater than zero.
 */
public record CostCenterBudgetRequest(
        @NotNull UUID costCenterId,
        @NotBlank @Size(max = 255) String fiscalYear,
        @NotNull @Positive BigDecimal budgetAmount,
        @PositiveOrZero BigDecimal availableBudget,
        @PositiveOrZero BigDecimal rolloverFromPrevious,
        Boolean allowRollover,
        @PositiveOrZero BigDecimal rolloverCap,
        @PositiveOrZero BigDecimal warningThreshold,
        UUID rolloverSourceBudgetId
) {
    /** Backward-compatible overload for every call site written before Phase 3's rollover fields existed - defaults each new field to "not supplied". */
    public CostCenterBudgetRequest(UUID costCenterId, String fiscalYear, BigDecimal budgetAmount, BigDecimal availableBudget) {
        this(costCenterId, fiscalYear, budgetAmount, availableBudget, null, null, null, null, null);
    }
}
