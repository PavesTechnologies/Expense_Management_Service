package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseReport;
import java.math.BigDecimal;

/**
 * The one canonical "amount the employee is owed" for a report, net of cash advance adjustments.
 *
 * <p>Used by AP payment (the amount AP must pay) and by supplementary claim eligibility (the amount a report still owes
 * before it counts as settled). Both must agree exactly, so the calculation lives here and nowhere else.
 */
final class SettlementAmounts {

    /** Tolerance, in base currency, when comparing a paid amount with the amount owed. */
    static final BigDecimal PAYMENT_TOLERANCE = new BigDecimal("0.01");

    private SettlementAmounts() {
    }

    /** Net of cash-advance offsets - what the employee is actually owed. */
    static BigDecimal amountOwed(ExpenseReport report) {
        return report.getReimbursableAmount() != null ? report.getReimbursableAmount()
                : ReimbursementCalculator.reimbursable(report.getTotalAmount(), report.getCashAdvanceAdjustments());
    }
}
