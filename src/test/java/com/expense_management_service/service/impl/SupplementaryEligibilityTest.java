package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.CashAdvanceRecoveryCase;
import com.expense_management_service.entity.SalaryDeductionRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Eligibility rules for a supplementary claim, including the worked examples from the approved brief. */
class SupplementaryEligibilityTest {

    // ---------------------------------------------------------- salary deductions

    @Test
    void payrollFailed_blocks_evenWhenTheEntityReportsItClosed() {
        assertThat(SupplementaryEligibility.salaryDeductionBlocks(false, SalaryDeductionRequest.PAYROLL_FAILED)).isTrue();
    }

    @Test
    void openSalaryDeductionBlocks_andAConfirmedOneDoesNot() {
        assertThat(SupplementaryEligibility.salaryDeductionBlocks(true, SalaryDeductionRequest.SENT_TO_PAYROLL)).isTrue();
        assertThat(SupplementaryEligibility.salaryDeductionBlocks(false, SalaryDeductionRequest.PAYROLL_CONFIRMED)).isFalse();
    }

    @Test
    void unknownSalaryDeductionStatus_failsClosed() {
        assertThat(SupplementaryEligibility.salaryDeductionBlocks(false, null)).isTrue();
    }

    // ---------------------------------------------------------- recovery cases

    @Test
    void recoveryOpenStates_block() {
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.NOTICE_SENT)).isTrue();
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.PENDING_FINANCE_REVIEW)).isTrue();
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.SENT_TO_PAYROLL)).isTrue();
    }

    @Test
    void recoveryFinishedStates_do_notBlock() {
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.RETURNED)).isFalse();
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.CANCELLED)).isFalse();
        assertThat(SupplementaryEligibility.recoveryCaseBlocks(CashAdvanceRecoveryCase.PAYROLL_RECORDED)).isFalse();
    }

    // ---------------------------------------------------------- carry-forwards

    @Test
    void onlyProposedCarryForward_blocks_appliedIsSettledAgainstTheDestinationAdvance() {
        assertThat(SupplementaryEligibility.carryForwardBlocks(CashAdvanceCarryForward.PROPOSED)).isTrue();
        assertThat(SupplementaryEligibility.carryForwardBlocks(CashAdvanceCarryForward.APPLIED)).isFalse();
    }

    @Test
    void rejectedAndReversedCarryForwards_do_notBlock() {
        assertThat(SupplementaryEligibility.carryForwardBlocks(CashAdvanceCarryForward.REJECTED)).isFalse();
        assertThat(SupplementaryEligibility.carryForwardBlocks(CashAdvanceCarryForward.REVERSED)).isFalse();
    }

    // ---------------------------------------------------------- AP predicate

    @Test
    void apSettled_requiresCompletedStatus_timestamp_andTheFullAmount() {
        BigDecimal owed = new BigDecimal("10000.00");
        assertThat(SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("10000.00"), owed)).isTrue();
        assertThat(SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", false, new BigDecimal("10000.00"), owed)).isFalse();
        assertThat(SupplementaryEligibility.apSettled("APPROVED_FOR_PAYMENT", true, new BigDecimal("10000.00"), owed)).isFalse();
    }

    @Test
    void apSettled_toleranceIsOneHundredthOfAUnit() {
        BigDecimal owed = new BigDecimal("4000.00");
        assertThat(SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("4000.01"), owed)).isTrue();
        assertThat(SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("4000.02"), owed)).isFalse();
    }

    @Test
    void partialAp_isNotSettled() {
        assertThat(SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("2000.00"),
                new BigDecimal("4000.00"))).isFalse();
    }

    // ---------------------------------------------------------- advance predicate

    @Test
    void advanceReconciled_requiresClosedStamp_andNoOpenItems() {
        assertThat(SupplementaryEligibility.advanceReconciled("CLOSED", true, false)).isTrue();
        assertThat(SupplementaryEligibility.advanceReconciled("CLOSED", false, false)).isFalse();
        assertThat(SupplementaryEligibility.advanceReconciled("CLOSED", true, true)).isFalse();
        assertThat(SupplementaryEligibility.advanceReconciled("SETTLEMENT_PENDING", true, false)).isFalse();
    }

    // ---------------------------------------------------------- worked examples

    @Test
    void example_noAdvance_mustBePaidThroughAp() {
        // Report ₹10,000, no advance. Eligible only when AP has paid the full ₹10,000.
        BigDecimal owed = new BigDecimal("10000.00");
        assertThat(SupplementaryEligibility.reportEligible(owed, false, false,
                SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("10000.00"), owed))).isTrue();
        assertThat(SupplementaryEligibility.reportEligible(owed, false, false,
                SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("5000.00"), owed))).isFalse();
    }

    @Test
    void example_fullyCoveredByAdvance_needsOnlyReconciliation() {
        // Report ₹10,000, advance ₹10,000. Owed is zero, so no AP payment is needed.
        BigDecimal owed = BigDecimal.ZERO;
        assertThat(SupplementaryEligibility.reportEligible(owed, true, true, false)).isTrue();
        assertThat(SupplementaryEligibility.reportEligible(owed, true, false, false)).isFalse();
    }

    @Test
    void example_partlyCovered_needsBothReconciliationAndRemainingAp() {
        // Report ₹10,000, advance ₹6,000, owed ₹4,000. Eligible only when the advance is reconciled and AP paid ₹4,000.
        BigDecimal owed = new BigDecimal("4000.00");
        boolean apPaid = SupplementaryEligibility.apSettled("PAYMENT_COMPLETED", true, new BigDecimal("4000.00"), owed);
        assertThat(SupplementaryEligibility.reportEligible(owed, true, true, apPaid)).isTrue();
        assertThat(SupplementaryEligibility.reportEligible(owed, true, false, apPaid)).isFalse();
        assertThat(SupplementaryEligibility.reportEligible(owed, true, true, false)).isFalse();
    }
}
