package com.expense_management_service.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDayCalculatorTest {

    // 2026-07-24 is a Friday, 2026-07-25/26 is the following Sat/Sun, 2026-07-27 is the next Monday.
    private static final LocalDateTime FRIDAY = LocalDateTime.of(2026, 7, 24, 9, 0);
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 7, 27, 9, 0);

    @Test
    void addBusinessDays_skipsTheWeekend_whenSpanningFridayToMonday() {
        LocalDateTime result = BusinessDayCalculator.addBusinessDays(FRIDAY, 1);

        assertThat(result).isEqualTo(MONDAY);
    }

    @Test
    void addBusinessDays_addsPlainWeekdays_whenNoWeekendInRange() {
        LocalDateTime result = BusinessDayCalculator.addBusinessDays(MONDAY, 2);

        assertThat(result).isEqualTo(LocalDateTime.of(2026, 7, 29, 9, 0));
    }

    @Test
    void addBusinessDays_returnsSameInstant_whenZeroDaysRequested() {
        LocalDateTime result = BusinessDayCalculator.addBusinessDays(MONDAY, 0);

        assertThat(result).isEqualTo(MONDAY);
    }

    @Test
    void addBusinessDays_throwsIllegalArgumentException_whenNegativeDaysRequested() {
        assertThatThrownBy(() -> BusinessDayCalculator.addBusinessDays(MONDAY, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
