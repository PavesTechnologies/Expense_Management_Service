package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Test double for {@link CashAdvanceBalanceCalculator} for unit tests that focus on something other than the balance
 * calculation. It answers with the advance's cached balance (or its amount when none is cached), which is what those
 * tests were written against. Tests of the calculator itself use the real class with mocked repositories.
 */
public final class TestBalanceCalculators {

    private TestBalanceCalculators() {
    }

    public static CashAdvanceBalanceCalculator storedCache() {
        CashAdvanceBalanceCalculator calculator = mock(CashAdvanceBalanceCalculator.class);
        stubStoredCache(calculator);
        return calculator;
    }

    /** Applies the cached-value answers to an existing (injected) mock. */
    public static void stubStoredCache(CashAdvanceBalanceCalculator calculator) {
        lenient().when(calculator.outstanding(any())).thenAnswer(inv -> {
            CashAdvance a = inv.getArgument(0);
            return a.getOutstandingBalance() != null ? a.getOutstandingBalance() : a.getAmount();
        });
        lenient().when(calculator.rawOutstanding(any())).thenAnswer(inv -> {
            CashAdvance a = inv.getArgument(0);
            return a.getOutstandingBalance() != null ? a.getOutstandingBalance() : a.getAmount();
        });
        lenient().when(calculator.rawOutstandingFor(any())).thenAnswer(inv -> {
            java.util.Map<java.util.UUID, BigDecimal> out = new java.util.HashMap<>();
            for (Object o : (java.util.Collection<?>) inv.getArgument(0)) {
                CashAdvance a = (CashAdvance) o;
                out.put(a.getAdvanceId(), a.getOutstandingBalance() != null ? a.getOutstandingBalance() : a.getAmount());
            }
            return out;
        });
    }
}
