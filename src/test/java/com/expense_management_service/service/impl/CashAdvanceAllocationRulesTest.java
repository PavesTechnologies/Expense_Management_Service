package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.enums.CashAdvanceRequestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Pure allocation rules for the two cash advance request types. */
class CashAdvanceAllocationRulesTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @ParameterizedTest
    @ValueSource(strings = {"DISBURSED", "IN_PROGRESS", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "ADJUSTED", "disbursed"})
    void rolloverEligible_forDisbursedAdvancesWhoseExpensesAreFinal(String status) {
        assertTrue(CashAdvanceAllocationRules.rolloverEligible(status));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Expenses submitted but not yet approved: the balance is not final, so it cannot be rolled over.
            "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED",
            // Not disbursed, so there is no cash out to roll over.
            "DRAFT", "SUBMITTED", "PENDING_MANAGER_APPROVAL", "PENDING_FINANCE_APPROVAL", "APPROVED", "DEFERRED", "QUERY_RAISED",
            // Overdue needs a separate Finance authorisation, never a normal rollover.
            "OVERDUE",
            // Closed or cancelled.
            "REJECTED", "CANCELLED", "CLOSED", "SETTLED",
            // Not confirmed as real statuses yet; ineligible until verified.
            "PENDING_REVIEW", "PENDING_CLOSURE",
            // Not in the carry-forward source list.
            "AWAITING_REIMBURSEMENT", "SUBMITTED_FOR_REVIEW", "UNDER_REVIEW", "RECONCILIATION_PENDING"})
    void notRolloverEligible_forEveryOtherStatus(String status) {
        assertFalse(CashAdvanceAllocationRules.rolloverEligible(status));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void notRolloverEligible_whenStatusIsMissing(String status) {
        assertFalse(CashAdvanceAllocationRules.rolloverEligible(status));
    }

    @Test
    void nullTypeIsCompleteNew() {
        assertEquals(CashAdvanceRequestType.COMPLETE_NEW, CashAdvanceAllocationRules.typeOrDefault(null));
        assertEquals(CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING,
                CashAdvanceAllocationRules.typeOrDefault(CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING));
    }

    @Test
    void completeNew_needsJustificationOnlyWhenAnOutstandingBalanceExists() {
        assertTrue(CashAdvanceAllocationRules.justificationRequired(CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("4000")));
        assertFalse(CashAdvanceAllocationRules.justificationRequired(CashAdvanceRequestType.COMPLETE_NEW, ZERO));
        assertFalse(CashAdvanceAllocationRules.justificationRequired(CashAdvanceRequestType.COMPLETE_NEW, null));
    }

    @Test
    void advancePlusOutstanding_never_needsAJustification() {
        assertFalse(CashAdvanceAllocationRules.justificationRequired(
                CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, new BigDecimal("4000")));
    }

    @Test
    void totalAllocation_isEligibleBalancePlusAmount_forAdvancePlusOutstanding() {
        // The example from the spec: 4,000 outstanding + 4,000 additional = 8,000 total allocation.
        assertEquals(0, new BigDecimal("8000").compareTo(CashAdvanceAllocationRules.totalAllocation(
                CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, new BigDecimal("4000"), new BigDecimal("4000"))));
    }

    @Test
    void totalAllocation_isOnlyTheAmount_forCompleteNew() {
        // The outstanding balance is not part of a complete new advance, even when one exists.
        assertEquals(0, new BigDecimal("8000").compareTo(CashAdvanceAllocationRules.totalAllocation(
                CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("4000"), new BigDecimal("8000"))));
    }

    @Test
    void justification_isRequiredWhenRequested_andTrimmed() {
        assertThrows(BusinessRuleViolationException.class,
                () -> CashAdvanceAllocationRules.validJustification(true, null));
        assertThrows(BusinessRuleViolationException.class,
                () -> CashAdvanceAllocationRules.validJustification(true, "   "));
        assertEquals("Client wants an onsite workshop in Pune",
                CashAdvanceAllocationRules.validJustification(true, "  Client wants an onsite workshop in Pune  "));
    }

    @Test
    void justification_mustBeLongEnoughToExplain() {
        assertThrows(BusinessRuleViolationException.class,
                () -> CashAdvanceAllocationRules.validJustification(true, "Urgent trip"));
    }

    @Test
    void justification_isDroppedWhenNotRequired_andNotGiven() {
        assertNull(CashAdvanceAllocationRules.validJustification(false, null));
        assertNull(CashAdvanceAllocationRules.validJustification(false, " "));
    }

    @Test
    void justification_isKeptWhenNotRequired_butGiven() {
        assertEquals("Client visit to Chennai office, separate trip",
                CashAdvanceAllocationRules.validJustification(false, "Client visit to Chennai office, separate trip"));
    }

    @ParameterizedTest
    @CsvSource({"19", "1"})
    void justification_belowTheMinimum_isRejectedEvenWhenNotRequired(int length) {
        String tooShort = "x".repeat(length);
        assertThrows(BusinessRuleViolationException.class,
                () -> CashAdvanceAllocationRules.validJustification(false, tooShort));
    }
}
