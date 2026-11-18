package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.SalaryDeductionRequest;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Eligibility rules for a Supplementary Expense Claim against a settled report. Pure functions over the persisted
 * states, so the rules can be tested without a database. Unknown or missing states count as blocking: an unresolved
 * recovery must never be mistaken for a finished one.
 */
final class SupplementaryEligibility {

    /** Recovery cases that still need work: a notice is out, a Finance review is waiting, or Payroll has the case. */
    static final Set<String> RECOVERY_OPEN = Set.of(
            CashAdvanceRecoveryCase.NOTICE_SENT,
            CashAdvanceRecoveryCase.PENDING_FINANCE_REVIEW,
            CashAdvanceRecoveryCase.SENT_TO_PAYROLL);

    /** Carry-forwards that still affect the advance: waiting for approval, or applied and not yet reversed. */
    static final Set<String> CARRY_FORWARD_BLOCKING = Set.of(
            CashAdvanceCarryForward.PROPOSED,
            CashAdvanceCarryForward.APPLIED);

    private SupplementaryEligibility() {
    }

    /**
     * A salary deduction blocks while the entity reports it open (its own lifecycle), or when Payroll failed to apply
     * it. A failed deduction does not prove the money was recovered, so it stays blocking until Finance resolves it.
     */
    static boolean salaryDeductionBlocks(boolean entityIsOpen, String status) {
        return entityIsOpen || status == null || SalaryDeductionRequest.PAYROLL_FAILED.equals(status);
    }

    static boolean recoveryCaseBlocks(String status) {
        return status == null || RECOVERY_OPEN.contains(status);
    }

    static boolean carryForwardBlocks(String status) {
        return status == null || CARRY_FORWARD_BLOCKING.contains(status);
    }

    /**
     * AP settlement predicate: the report is paid in full through AP. The paid amount must match the amount owed within
     * the settlement tolerance, using the same tolerance as the AP single-payment path.
     */
    static boolean apSettled(String paymentRoutingStatus, boolean paymentCompletedAtSet, BigDecimal paidAmount,
                             BigDecimal amountOwed) {
        if (!"PAYMENT_COMPLETED".equals(paymentRoutingStatus) || !paymentCompletedAtSet || paidAmount == null
                || amountOwed == null) {
            return false;
        }
        return paidAmount.subtract(amountOwed).abs().compareTo(SettlementAmounts.PAYMENT_TOLERANCE) <= 0;
    }

    /**
     * Cash advance reconciliation predicate: the advance is closed with its settlement stamp, and nothing open is
     * linked to it.
     */
    static boolean advanceReconciled(String advanceStatus, boolean settledAtSet, boolean anyOpenItem) {
        return "CLOSED".equals(advanceStatus) && settledAtSet && !anyOpenItem;
    }

    /**
     * Whole-report eligibility decision flow:
     * <ul>
     *   <li>No cash advance: the AP predicate must hold.</li>
     *   <li>Fully covered by an advance (owed is zero): the advance must be reconciled; no AP payment is needed.</li>
     *   <li>Partly covered: the advance must be reconciled and the remaining owed amount must be paid through AP.</li>
     * </ul>
     *
     * @param amountOwed        the canonical net amount owed ({@link SettlementAmounts#amountOwed})
     * @param hasAdvance        whether the report is linked to a cash advance
     * @param advanceReconciled result of {@link #advanceReconciled}; ignored when there is no advance
     * @param apSettled         result of {@link #apSettled}
     */
    static boolean reportEligible(BigDecimal amountOwed, boolean hasAdvance, boolean advanceReconciled, boolean apSettled) {
        if (!hasAdvance) {
            return apSettled;
        }
        if (!advanceReconciled) {
            return false;
        }
        return amountOwed != null && amountOwed.signum() == 0 || apSettled;
    }
}
