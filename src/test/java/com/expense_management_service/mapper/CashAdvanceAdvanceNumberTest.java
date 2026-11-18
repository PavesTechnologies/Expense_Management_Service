package com.expense_management_service.mapper;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cash advance reference numbers follow the expense report form and its April-to-March fiscal year. */
class CashAdvanceAdvanceNumberTest {

    private static final Pattern SHAPE = Pattern.compile("ADV-\\d{4}-\\d{4}-[0-9A-F]{8}");

    @Test
    void numberHasTheExpenseStyleShape() {
        String number = CashAdvanceMapper.newAdvanceNumber(LocalDate.of(2026, 11, 17));

        assertTrue(SHAPE.matcher(number).matches(), number);
        assertTrue(number.startsWith("ADV-2026-2027-"), number);
    }

    @Test
    void marchStillBelongsToThePreviousFiscalYear() {
        assertTrue(CashAdvanceMapper.newAdvanceNumber(LocalDate.of(2027, 3, 31)).startsWith("ADV-2026-2027-"));
    }

    @Test
    void aprilStartsTheNextFiscalYear() {
        assertTrue(CashAdvanceMapper.newAdvanceNumber(LocalDate.of(2027, 4, 1)).startsWith("ADV-2027-2028-"));
    }

    @Test
    void numbersDoNotRepeatForTheSameDay() {
        String first = CashAdvanceMapper.newAdvanceNumber(LocalDate.of(2026, 11, 17));
        String second = CashAdvanceMapper.newAdvanceNumber(LocalDate.of(2026, 11, 17));

        assertEquals(false, first.equals(second));
    }
}
