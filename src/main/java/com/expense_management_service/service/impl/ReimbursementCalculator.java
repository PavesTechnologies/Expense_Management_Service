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

    static BigDecimal reimbursable(BigDecimal gross, Collection<CashAdvanceAdjustment> adjustments) {
        BigDecimal total = gross != null ? gross : BigDecimal.ZERO;
        BigDecimal adjusted = adjustments == null ? BigDecimal.ZERO : adjustments.stream()
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
