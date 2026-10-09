package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @param availableBudget    budget less what has actually been paid out (consumed on payment)
 * @param reservedAmount     held by submitted reports not yet paid - in approval, in Finance
 *                           verification, or approved and awaiting payment (ACTIVE encumbrances)
 * @param effectiveAvailable {@code availableBudget - reservedAmount}: what a new submission can
 *                           still use, and the figure the submission-time budget check enforces
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
        BigDecimal effectiveAvailable
) {

    public CostCenterBudgetResponse(UUID budgetId, UUID costCenterId, String costCenterName, String fiscalYear,
                                    BigDecimal budgetAmount, BigDecimal availableBudget, BigDecimal rolloverFromPrevious,
                                    Boolean allowRollover, BigDecimal rolloverCap, BigDecimal warningThreshold,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(budgetId, costCenterId, costCenterName, fiscalYear, budgetAmount, availableBudget, rolloverFromPrevious,
                allowRollover, rolloverCap, warningThreshold, createdAt, updatedAt, null, null);
    }
}
