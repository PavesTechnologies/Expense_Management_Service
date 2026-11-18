package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression for the shared amount-owed calculation. {@code legacy} below is the exact formula that AP payment used
 * before the move; every scenario must give the same result from the shared method.
 */
class SettlementAmountsTest {

    /** The original AP formula, copied verbatim before it was moved to {@link SettlementAmounts}. */
    private static BigDecimal legacyAmountOwed(ExpenseReport report) {
        return report.getReimbursableAmount() != null ? report.getReimbursableAmount()
                : ReimbursementCalculator.reimbursable(report.getTotalAmount(), report.getCashAdvanceAdjustments());
    }

    private static ExpenseReport report(String total, String reimbursable, CashAdvanceAdjustment... adjustments) {
        ExpenseReport report = new ExpenseReport();
        report.setTotalAmount(total == null ? null : new BigDecimal(total));
        report.setReimbursableAmount(reimbursable == null ? null : new BigDecimal(reimbursable));
        report.setCashAdvanceAdjustments(new java.util.ArrayList<>(List.of(adjustments)));
        return report;
    }

    private static CashAdvanceAdjustment adjustment(String amount, boolean active) {
        CashAdvanceAdjustment adjustment = mock(CashAdvanceAdjustment.class);
        when(adjustment.isActive()).thenReturn(active);
        when(adjustment.getAdjustedAmount()).thenReturn(new BigDecimal(amount));
        return adjustment;
    }

    private static void assertSameAsLegacy(ExpenseReport report) {
        BigDecimal shared = SettlementAmounts.amountOwed(report);
        BigDecimal legacy = legacyAmountOwed(report);
        assertThat(shared).isEqualByComparingTo(legacy);
        assertThat(shared.scale()).isEqualTo(legacy.scale());
    }

    @Test
    void storedReimbursableAmount_isUsedWhenPresent() {
        ExpenseReport report = report("10000.00", "4000.00");
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("4000.00");
    }

    @Test
    void zeroStoredReimbursableAmount_isStillUsedAsIs() {
        // A stored zero is a real value (fully covered), not a missing one.
        ExpenseReport report = report("10000.00", "0.00");
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("0");
    }

    @Test
    void withoutStoredAmount_totalMinusActiveAdjustments() {
        ExpenseReport report = report("10000.00", null, adjustment("6000.00", true));
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("4000.00");
    }

    @Test
    void inactiveAdjustments_areNotDeducted() {
        ExpenseReport report = report("10000.00", null, adjustment("6000.00", false));
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("10000.00");
    }

    @Test
    void adjustmentsLargerThanTheTotal_floorAtZero() {
        ExpenseReport report = report("5000.00", null, adjustment("7000.00", true));
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("0");
    }

    @Test
    void multipleAdjustments_areSummed() {
        ExpenseReport report = report("10000.00", null, adjustment("2500.00", true), adjustment("1500.00", true));
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("6000.00");
    }

    @Test
    void missingTotalAndNoAdjustments_isZero() {
        ExpenseReport report = report(null, null);
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report)).isEqualByComparingTo("0");
    }

    @Test
    void fourDecimalStoredPrecision_isPreserved() {
        ExpenseReport report = report("1234.5678", "1234.5678");
        assertSameAsLegacy(report);
        assertThat(SettlementAmounts.amountOwed(report).scale()).isEqualTo(4);
    }

    @Test
    void tolerance_isOneHundredthOfAUnit() {
        assertThat(SettlementAmounts.PAYMENT_TOLERANCE).isEqualByComparingTo("0.01");
    }
}
