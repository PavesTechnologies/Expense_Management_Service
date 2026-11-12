package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseLineItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReimbursementCalculatorTest {

    private static CashAdvanceAdjustment adj(String amount) {
        return CashAdvanceAdjustment.builder().adjustedAmount(new BigDecimal(amount)).build();
    }

    @Test
    void reimbursable_isGrossLessCashAdvanceAdjustments() {
        assertThat(ReimbursementCalculator.reimbursable(new BigDecimal("11800"), List.of(adj("5000"), adj("1000"))))
                .isEqualByComparingTo("5800");
    }

    @Test
    void reimbursable_isGross_withoutAdvances_andRecoverableTaxNeverReducesIt() {
        assertThat(ReimbursementCalculator.reimbursable(new BigDecimal("11800"), List.of())).isEqualByComparingTo("11800");
        assertThat(ReimbursementCalculator.reimbursable(new BigDecimal("11800"), null)).isEqualByComparingTo("11800");
    }

    @Test
    void reimbursable_neverGoesNegative() {
        assertThat(ReimbursementCalculator.reimbursable(new BigDecimal("3000"), List.of(adj("5000")))).isEqualByComparingTo("0");
    }

    @Test
    void costBasis_isGrossLessRecoverableTax() {
        ExpenseLineItem line = ExpenseLineItem.builder().baseAmount(new BigDecimal("11800")).baseRecoverableTaxAmount(new BigDecimal("900")).build();
        assertThat(BillingPayloadFactory.costBasis(line)).isEqualByComparingTo("10900");
        assertThat(BillingPayloadFactory.costBasis(ExpenseLineItem.builder().baseAmount(new BigDecimal("500")).build()))
                .isEqualByComparingTo("500");
    }
}
