package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseLineItem;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * BR-TAX-013: the employee is reimbursed the gross amount they paid, less cash advance
 * adjustments, never below zero. Recoverable tax is the company's claim on the tax authority
 * and never reduces reimbursement. All amounts are base currency.
 */
final class ReimbursementCalculator {

    private ReimbursementCalculator() {
    }

    /**
     * Amounts under this (base currency) are written off, not paid: an AP payment for less than a rupee costs more
     * than the money itself. The write-off is applied when a report is approved and when its advance offsets change.
     */
    static final BigDecimal WRITE_OFF_BELOW = new BigDecimal("1.00");

    /** The amount AP is asked to pay: zero when the owed amount is under the write-off threshold. */
    static BigDecimal payable(BigDecimal reimbursable) {
        if (reimbursable == null || reimbursable.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return reimbursable.compareTo(WRITE_OFF_BELOW) < 0 ? BigDecimal.ZERO : reimbursable;
    }

    static BigDecimal reimbursable(BigDecimal gross, Collection<CashAdvanceAdjustment> adjustments) {
        BigDecimal total = gross != null ? gross : BigDecimal.ZERO;
        BigDecimal adjusted = adjustments == null ? BigDecimal.ZERO : adjustments.stream()
                .filter(CashAdvanceAdjustment::isActive)
                .map(CashAdvanceAdjustment::getAdjustedAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.subtract(adjusted).max(BigDecimal.ZERO);
    }

    /** Sum of a nullable line amount over lines (e.g. base tax). */
    static BigDecimal sum(Collection<ExpenseLineItem> lines, java.util.function.Function<ExpenseLineItem, BigDecimal> amount) {
        return lines == null ? BigDecimal.ZERO : lines.stream()
                .map(amount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
