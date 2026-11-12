package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollMonthPolicyTest {

    @Test
    void windowStartsAtTheCurrentMonthAndHasTheConfiguredLength() {
        PayrollMonthPolicy policy = new PayrollMonthPolicy(12, 0);

        assertThat(policy.eligibleMonths(LocalDate.of(2026, 10, 26)))
                .hasSize(12)
                .startsWith(YearMonth.of(2026, 10))
                .endsWith(YearMonth.of(2027, 9));
    }

    @Test
    void cutoffMovesTheWindowToTheNextMonthOnceTheCutoffDayHasPassed() {
        PayrollMonthPolicy policy = new PayrollMonthPolicy(12, 20);

        assertThat(policy.firstEligibleMonth(LocalDate.of(2026, 10, 20))).isEqualTo(YearMonth.of(2026, 10));
        assertThat(policy.firstEligibleMonth(LocalDate.of(2026, 10, 21))).isEqualTo(YearMonth.of(2026, 11));
    }

    @Test
    void requireEligibleAcceptsTheCurrentAndFutureMonthsInTheWindow() {
        PayrollMonthPolicy policy = new PayrollMonthPolicy(12, 0);
        LocalDate today = LocalDate.of(2026, 10, 26);

        assertThat(policy.requireEligible("2026-10", today)).isEqualTo(YearMonth.of(2026, 10));
        assertThat(policy.requireEligible(" 2027-09 ", today)).isEqualTo(YearMonth.of(2027, 9));
    }

    @Test
    void requireEligibleRejectsPastMonthsBeyondTheWindowAndMalformedValues() {
        PayrollMonthPolicy policy = new PayrollMonthPolicy(12, 0);
        LocalDate today = LocalDate.of(2026, 10, 26);

        assertThatThrownBy(() -> policy.requireEligible("2026-09", today)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> policy.requireEligible("2027-10", today)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> policy.requireEligible("October 2026", today)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> policy.requireEligible(null, today)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void configurationIsValidated() {
        assertThatThrownBy(() -> new PayrollMonthPolicy(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayrollMonthPolicy(12, 29)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayrollMonthPolicy(12, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
