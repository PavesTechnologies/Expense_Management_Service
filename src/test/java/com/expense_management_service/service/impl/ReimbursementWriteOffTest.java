package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReimbursementWriteOffTest {

    @Test
    void amountUnderOneRupee_isWrittenOff_toZero() {
        assertThat(ReimbursementCalculator.payable(new BigDecimal("0.50"))).isEqualByComparingTo("0");
        assertThat(ReimbursementCalculator.payable(new BigDecimal("0.99"))).isEqualByComparingTo("0");
    }

    @Test
    void amountOfOneRupeeOrMore_isPaidAsIs() {
        assertThat(ReimbursementCalculator.payable(new BigDecimal("1.00"))).isEqualByComparingTo("1.00");
        assertThat(ReimbursementCalculator.payable(new BigDecimal("46.00"))).isEqualByComparingTo("46.00");
    }

    @Test
    void zeroNegativeOrMissing_staysZero() {
        assertThat(ReimbursementCalculator.payable(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(ReimbursementCalculator.payable(new BigDecimal("-3"))).isEqualByComparingTo("0");
        assertThat(ReimbursementCalculator.payable(null)).isEqualByComparingTo("0");
    }
}
