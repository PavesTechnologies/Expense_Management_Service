package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Non-blocking signal: this Cost Center's Effective Available Budget, after the just-created
 * encumbrance, fell below its configured {@code warningThreshold} while remaining &gt;= 0. Never
 * causes a submission to fail - callers (Phase 4) surface this to the Cost Center Owner exactly like
 * an existing policy-violation warning, per the locked "warn only" rule.
 */
public record BudgetWarning(
        UUID costCenterId,
        String costCenterCode,
        BigDecimal effectiveAvailableAfterEncumbrance,
        BigDecimal warningThreshold
) {
}
