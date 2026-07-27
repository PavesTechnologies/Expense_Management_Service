package com.expense_management_service.common;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

/**
 * Weekday-based business-day arithmetic, used for SLA due-date calculation
 * (EP06 plan, Phases 1 and 4). Weekends (Saturday/Sunday) are skipped; there
 * is no holiday calendar anywhere in this codebase yet, so public holidays
 * are deliberately not accounted for.
 */
public final class BusinessDayCalculator {

    private BusinessDayCalculator() {
    }

    /** Adds {@code businessDays} to {@code start}, skipping Saturdays and Sundays. */
    public static LocalDateTime addBusinessDays(LocalDateTime start, int businessDays) {
        if (businessDays < 0) {
            throw new IllegalArgumentException("businessDays must be non-negative");
        }

        LocalDateTime result = start;
        int remaining = businessDays;
        while (remaining > 0) {
            result = result.plusDays(1);
            if (isBusinessDay(result)) {
                remaining--;
            }
        }
        return result;
    }

    private static boolean isBusinessDay(LocalDateTime dateTime) {
        DayOfWeek day = dateTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
