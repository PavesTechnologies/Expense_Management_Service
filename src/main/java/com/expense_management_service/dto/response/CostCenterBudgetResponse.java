package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Budget row plus the derived figures the budget page shows.
 *
 * @param availableBudget        pool less everything charged so far: paid expenses, approved cash
 *                               advance principal and approved cash advance fees
 * @param reservedAmount         held by submitted reports not yet paid (ACTIVE encumbrances)
 * @param effectiveAvailable     {@code availableBudget - reservedAmount}: what a new submission can
 *                               still use, and the figure the submission-time budget check enforces
 * @param cashAdvanceAllocated   total approved cash advance principal (already deducted from availableBudget)
 * @param cashAdvanceFeeAllocated total approved cash advance fees (already deducted from availableBudget)
 * @param actualUtilized         money actually spent: {@code pool - availableBudget - cashAdvanceAllocated -
 *                               cashAdvanceFeeAllocated}, never negative. Excludes advances and fees, so
 *                               each unit of money appears in exactly one of the buckets.
 */
public record CostCenterBudgetResponse(
        UUID budgetId,
        UUID costCenterId,
        String costCenterName,
        String fiscalYear,
        BigDecimal budgetAmount,
        BigDecimal availableBudget,
        BigDecimal rolloverFromPrevious,
        Boolean allowRollover,
        BigDecimal rolloverCap,
        BigDecimal warningThreshold,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        BigDecimal reservedAmount,
        BigDecimal effectiveAvailable,
        BigDecimal cashAdvanceAllocated,
        BigDecimal cashAdvanceFeeAllocated,
        BigDecimal actualUtilized
) {

    public CostCenterBudgetResponse(UUID budgetId, UUID costCenterId, String costCenterName, String fiscalYear,
                                    BigDecimal budgetAmount, BigDecimal availableBudget, BigDecimal rolloverFromPrevious,
                                    Boolean allowRollover, BigDecimal rolloverCap, BigDecimal warningThreshold,
                                    LocalDateTime createdAt, LocalDateTime updatedAt,
                                    BigDecimal reservedAmount, BigDecimal effectiveAvailable) {
        this(budgetId, costCenterId, costCenterName, fiscalYear, budgetAmount, availableBudget, rolloverFromPrevious,
                allowRollover, rolloverCap, warningThreshold, createdAt, updatedAt, reservedAmount, effectiveAvailable,
                null, null, null);
    }

    public CostCenterBudgetResponse(UUID budgetId, UUID costCenterId, String costCenterName, String fiscalYear,
                                    BigDecimal budgetAmount, BigDecimal availableBudget, BigDecimal rolloverFromPrevious,
                                    Boolean allowRollover, BigDecimal rolloverCap, BigDecimal warningThreshold,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(budgetId, costCenterId, costCenterName, fiscalYear, budgetAmount, availableBudget, rolloverFromPrevious,
                allowRollover, rolloverCap, warningThreshold, createdAt, updatedAt, null, null, null, null, null);
    }
}
