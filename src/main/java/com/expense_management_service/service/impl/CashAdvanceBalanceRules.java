package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.ExpenseReport;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single rule for when a cash advance is settled, shared by every path that changes its balance
 * (offset, repayment, and report-approval recalculation).
 *
 * <ul>
 *   <li>A balance is compared at the currency's precision, so a sub-unit residue cannot keep an advance open.</li>
 *   <li>An advance is {@code CLOSED} only when its balance is zero and no reimbursement is owed. Any positive balance is
 *       {@code SETTLEMENT_PENDING}. A zero balance with a reimbursement still owed is {@code AWAITING_REIMBURSEMENT}.</li>
 *   <li>A balance that would go negative is an overpayment or over-offset, and is rejected.</li>
 * </ul>
 */
public final class CashAdvanceBalanceRules {

    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_SETTLEMENT_PENDING = "SETTLEMENT_PENDING";
    /** Balance is zero, but a linked report still owes the employee a reimbursement. Closes once that is paid. */
    public static final String STATUS_AWAITING_REIMBURSEMENT = "AWAITING_REIMBURSEMENT";

    private CashAdvanceBalanceRules() {
    }

    /** Balance carried into the advance by carry-forward. Null on older rows means zero. */
    public static BigDecimal carriedIn(com.expense_management_service.entity.CashAdvance advance) {
        return advance.getCarriedInAmount() == null ? BigDecimal.ZERO : advance.getCarriedInAmount();
    }

    /** Balance this advance has carried out to other advances (carry-forward). Null on older rows means zero. */
    public static BigDecimal carriedOut(com.expense_management_service.entity.CashAdvance advance) {
        return advance.getCarriedOutAmount() == null ? BigDecimal.ZERO : advance.getCarriedOutAmount();
    }

    /**
     * The balance the records support: the advance amount, less what it has carried out, less the approved offsets and
     * the repayments. Carried-in balance is already part of the amount (the employee holds it), so it is not added again.
     * The stored outstanding balance is checked against this, and offsets set it from this.
     */
    public static BigDecimal ledgerBalance(com.expense_management_service.entity.CashAdvance advance,
                                           BigDecimal approvedOffsets, BigDecimal repaid) {
        BigDecimal obligation = advance.getAmount() == null ? BigDecimal.ZERO : advance.getAmount();
        return obligation.subtract(carriedOut(advance))
                .subtract(approvedOffsets == null ? BigDecimal.ZERO : approvedOffsets)
                .subtract(repaid == null ? BigDecimal.ZERO : repaid);
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
        return statusAfterBalance(balance, decimals, false);
    }

    /**
     * The status for this balance when some linked report still has an employee reimbursement unpaid. An offset can
     * bring the balance to zero while the company still owes the employee the rest of a report, so the advance stays
     * open until that reimbursement is paid and the advance is rechecked.
     */
    public static String statusAfterBalance(BigDecimal balance, int decimals, boolean reimbursementOwed) {
        boolean zero = requireNonNegative(balance, decimals).signum() == 0;
        if (!zero) {
            return STATUS_SETTLEMENT_PENDING;
        }
        return reimbursementOwed ? STATUS_AWAITING_REIMBURSEMENT : STATUS_CLOSED;
    }

    /**
     * Stamps when and by whom an advance was closed, and clears the stamp when it is open again. Every path that sets
     * the status must call this, so {@code settled_at} and {@code settled_by} match the CLOSED status.
     */
    public static void stampSettlement(com.expense_management_service.entity.CashAdvance advance, String status, String actor) {
        if (STATUS_CLOSED.equals(status)) {
            if (advance.getSettledAt() == null) {
                advance.setSettledAt(java.time.LocalDateTime.now());
                advance.setSettledBy(actor);
            }
        } else {
            advance.setSettledAt(null);
            advance.setSettledBy(null);
        }
    }

    /** True while a report still owes the employee money: its reimbursable amount is not yet fully paid. */
    public static boolean reimbursementOwed(ExpenseReport report) {
        return reimbursementStillOwed(report).signum() > 0;
    }

    /** The part of a report's reimbursable amount that has not been paid to the employee yet; zero when none is owed. */
    public static BigDecimal reimbursementStillOwed(ExpenseReport report) {
        if (report == null || report.getReimbursableAmount() == null || report.getReimbursableAmount().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = report.getPaidAmount() != null ? report.getPaidAmount() : BigDecimal.ZERO;
        return report.getReimbursableAmount().subtract(paid).max(BigDecimal.ZERO);
    }
}
