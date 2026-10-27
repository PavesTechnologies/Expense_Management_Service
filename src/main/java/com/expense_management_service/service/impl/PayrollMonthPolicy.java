package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Which payroll months a salary deduction may target.
 *
 * <p>The repository has no payroll calendar, so the defaults are deliberately generic: the current month and the
 * next {@code window-months - 1} months, with no cutoff. Set {@code expense.payroll.cutoff-day-of-month} (1-28) to
 * move to the following month once that day has passed. Company cutoff dates are not invented here.
 */
@Component
public class PayrollMonthPolicy {

    private final int windowMonths;
    private final int cutoffDayOfMonth;

    public PayrollMonthPolicy(
            @Value("${expense.payroll.window-months:12}") int windowMonths,
            @Value("${expense.payroll.cutoff-day-of-month:0}") int cutoffDayOfMonth) {
        if (windowMonths < 1) {
            throw new IllegalArgumentException("expense.payroll.window-months must be at least 1");
        }
        if (cutoffDayOfMonth < 0 || cutoffDayOfMonth > 28) {
            throw new IllegalArgumentException("expense.payroll.cutoff-day-of-month must be 0 (none) or 1-28");
        }
        this.windowMonths = windowMonths;
        this.cutoffDayOfMonth = cutoffDayOfMonth;
    }

    /** The first eligible month for {@code today}, honouring the cutoff. */
    public YearMonth firstEligibleMonth(LocalDate today) {
        YearMonth first = YearMonth.from(today);
        if (cutoffDayOfMonth > 0 && today.getDayOfMonth() > cutoffDayOfMonth) {
            first = first.plusMonths(1);
        }
        return first;
    }

    public List<YearMonth> eligibleMonths(LocalDate today) {
        YearMonth first = firstEligibleMonth(today);
        return IntStream.range(0, windowMonths).mapToObj(first::plusMonths).toList();
    }

    /** Parses a {@code yyyy-MM} value and returns it only if it is inside the eligible window. */
    public YearMonth requireEligible(String value, LocalDate today) {
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessRuleViolationException("Select a payroll month in the form yyyy-MM.");
        }
        if (!eligibleMonths(today).contains(parsed)) {
            throw new BusinessRuleViolationException(
                    "Payroll month " + parsed + " is outside the eligible window for salary deductions.");
        }
        return parsed;
    }
}
