package com.expense_management_service.service;

/**
 * Whether a line item is currently eligible for Finance VERIFY, and - if not - the single specific
 * reason to surface to the UI (spec: "Cannot verify line item. Reason: ..."). Checks are evaluated
 * in a fixed order (receipt, then policy, then GL account) and stop at the first failure, so {@code
 * reason} is never a list - only ever the first blocking problem.
 */
public record FinanceEligibilityResult(boolean eligible, String reason) {

    public static FinanceEligibilityResult ok() {
        return new FinanceEligibilityResult(true, null);
    }

    public static FinanceEligibilityResult blocked(String reason) {
        return new FinanceEligibilityResult(false, reason);
    }
}
