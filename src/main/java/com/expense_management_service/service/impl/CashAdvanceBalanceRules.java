package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single rule for when a cash advance is settled, shared by every path that changes its balance
 * (offset, repayment, and report-approval recalculation).
 *
 * <ul>
 *   <li>A balance is compared at the currency's precision, so a sub-unit residue cannot keep an advance open.</li>
 *   <li>An advance is {@code CLOSED} only when its balance is zero. Any positive balance is {@code SETTLEMENT_PENDING}.</li>
 *   <li>A balance that would go negative is an overpayment or over-offset, and is rejected.</li>
 * </ul>
 */
public final class CashAdvanceBalanceRules {

    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_SETTLEMENT_PENDING = "SETTLEMENT_PENDING";

    private CashAdvanceBalanceRules() {
    }

    /** Balance carried into the advance by carry-forward. Null on older rows means zero. */
    public static BigDecimal carriedIn(com.expense_management_service.entity.CashAdvance advance) {
        return advance.getCarriedInAmount() == null ? BigDecimal.ZERO : advance.getCarriedInAmount();
    }

    public static BigDecimal roundToCurrency(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.HALF_UP);
    }

    /** Rounds the balance to the currency's precision and rejects a negative result. */
    public static BigDecimal requireNonNegative(BigDecimal balance, int decimals) {
        BigDecimal rounded = roundToCurrency(balance, decimals);
        if (rounded.signum() < 0) {
            throw new BusinessRuleViolationException(
                    "Settlement would overpay the cash advance: resulting balance " + rounded
                            + " is negative. Amounts cannot exceed the outstanding balance.");
        }
        return rounded;
    }

    /** The status an advance must have for this balance. Closed only at exactly zero. */
    public static String statusAfterBalance(BigDecimal balance, int decimals) {
        return requireNonNegative(balance, decimals).signum() == 0 ? STATUS_CLOSED : STATUS_SETTLEMENT_PENDING;
    }
}
