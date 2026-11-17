package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Receipt dates as they are printed: month names in capitals, title case, or numeric. */
class ReceiptDateParsingTest {

    @Test
    void uppercaseMonthNameFromTheBillIsRead() {
        // The receipt prints "Date: 10 NOV 2026".
        assertThat(ReceiptFieldParsingUtils.parseDate("10 NOV 2026")).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void titleCaseAndLowerCaseMonthNamesAreRead() {
        assertThat(ReceiptFieldParsingUtils.parseDate("10 Nov 2026")).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(ReceiptFieldParsingUtils.parseDate("10 nov 2026")).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(ReceiptFieldParsingUtils.parseDate("NOV 10, 2026")).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void numericDatesStillParseDayFirst() {
        assertThat(ReceiptFieldParsingUtils.parseDate("05/06/2026")).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(ReceiptFieldParsingUtils.parseDate("2026-11-10")).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void unreadableTextGivesNull() {
        assertThat(ReceiptFieldParsingUtils.parseDate("Thank you")).isNull();
        assertThat(ReceiptFieldParsingUtils.parseDate(null)).isNull();
    }
}
