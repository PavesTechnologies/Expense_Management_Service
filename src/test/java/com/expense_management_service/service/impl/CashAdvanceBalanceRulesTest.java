package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression tests: an advance must not close while money is still owed, and must not be overpaid. */
class CashAdvanceBalanceRulesTest {

    @Test
    void zeroBalance_isClosed() {
        assertEquals("CLOSED", CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("0.00"), 2));
    }

    @Test
    void anyPositiveBalance_staysSettlementPending_notClosed() {
        // Regression: INR 133 owed must never be reported as closed
        assertEquals("SETTLEMENT_PENDING", CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("133.00"), 2));
    }

    @Test
    void oneCentOwed_staysOpen() {
        assertEquals("SETTLEMENT_PENDING", CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("0.01"), 2));
    }

    @Test
    void residueBelowCurrencyPrecision_isTreatedAsZero() {
        // 0.004 INR rounds to 0.00 at 2 decimals, so the advance settles at currency precision
        assertEquals("CLOSED", CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("0.004"), 2));
    }

    @Test
    void negativeBalance_isRejectedAsOverpayment() {
        assertThrows(BusinessRuleViolationException.class,
                () -> CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("-0.01"), 2));
    }

    @Test
    void roundingUsesTheCurrencysDecimalPlaces() {
        // A three-decimal currency keeps the extra digit, so 0.004 is still owed
        assertEquals("SETTLEMENT_PENDING", CashAdvanceBalanceRules.statusAfterBalance(new BigDecimal("0.004"), 3));
        assertEquals(0, new BigDecimal("12.346").compareTo(CashAdvanceBalanceRules.roundToCurrency(new BigDecimal("12.3456"), 3)));
    }
}
