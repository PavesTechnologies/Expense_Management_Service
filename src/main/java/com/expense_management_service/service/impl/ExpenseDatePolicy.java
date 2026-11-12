package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ExpenseDateRangeResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Which dates an expense line on a report may carry. The save path and the range endpoint both use this, so the
 * forms and the server always agree. Every rule is inclusive at its boundaries.
 * <ul>
 *   <li>A date after today is always refused.</li>
 *   <li>Normal report (no cash advance): the date must fall within the trip start and end dates, capped at today.
 *       A trip that has not started permits no date yet.</li>
 *   <li>Cash-advance-linked report: the date must fall within the trip dates, and no later than the advance's expense
 *       deadline ({@link CashAdvanceExpenseWindow#deadline}, 30 days after the trip end). The disbursement date does not
 *       limit expense dates, because cash often arrives after the trip has been paid for.</li>
 * </ul>
 * A report without trip dates has no trip bound. Its range is only capped at today (and the window, if linked).
 */
public final class ExpenseDatePolicy {

    private ExpenseDatePolicy() {
    }

    /** The permitted range for {@code report} as of {@code today} (business calendar), with the window length from config. */
    public static ExpenseDateRangeResponse range(ExpenseReport report, LocalDate today, int windowDays) {
        CashAdvance advance = report.getCashAdvance();
        if (advance != null) {
            return advanceRange(advance, report.getTripStartDate(), report.getTripEndDate(), today, windowDays);
        }
        return tripRange(report.getTripStartDate(), report.getTripEndDate(), today);
    }

    /** Throws {@link IllegalArgumentException} with the reason when {@code expenseDate} is not permitted. */
    public static void assertAllowed(ExpenseDateRangeResponse range, LocalDate expenseDate) {
        if (range.blockedReason() != null) {
            throw new IllegalArgumentException(range.blockedReason());
        }
        if (expenseDate.isAfter(range.today())) {
            throw new IllegalArgumentException("Expense date " + expenseDate + " cannot be in the future.");
        }
        boolean beforeStart = range.minDate() != null && expenseDate.isBefore(range.minDate());
        boolean afterEnd = range.maxDate() == null || expenseDate.isAfter(range.maxDate());
        if (beforeStart || afterEnd) {
            throw new IllegalArgumentException("Expense date " + expenseDate + " must fall within " + range.basis() + " ("
                    + describe(range) + ").");
        }
    }

    private static ExpenseDateRangeResponse tripRange(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null || end == null) {
            return new ExpenseDateRangeResponse(null, today, today, "the report's dates", null);
        }
        if (start.isAfter(today)) {
            return blocked(today, "The trip starts on " + start + ", so no expense can be dated yet.", "the report's trip dates");
        }
        LocalDate max = end.isBefore(today) ? end : today;
        return new ExpenseDateRangeResponse(start, max, today, "the report's trip dates", null);
    }

    private static ExpenseDateRangeResponse advanceRange(CashAdvance advance, LocalDate tripStart, LocalDate tripEnd,
                                                         LocalDate today, int windowDays) {
        String basis = "the report's trip dates and the cash advance expense deadline";
        if (tripStart != null && tripStart.isAfter(today)) {
            return blocked(today, "The trip starts on " + tripStart + ", so no expense can be dated yet.", basis);
        }

        LocalDate min = tripStart;
        LocalDate max = tripEnd != null && tripEnd.isBefore(today) ? tripEnd : today;
        if (!advance.isExpenseDeadlineReopened()) {
            LocalDateTime deadline = CashAdvanceExpenseWindow.deadline(advance, windowDays);
            if (deadline != null && deadline.toLocalDate().isBefore(max)) {
                max = deadline.toLocalDate();
            }
        }
        if (min != null && min.isAfter(max)) {
            return blocked(today, "No expense date is permitted: the expense deadline for this cash advance has passed.", basis);
        }
        return new ExpenseDateRangeResponse(min, max, today, basis, null);
    }

    private static ExpenseDateRangeResponse blocked(LocalDate today, String reason, String basis) {
        return new ExpenseDateRangeResponse(null, null, today, basis, reason);
    }

    private static String describe(ExpenseDateRangeResponse range) {
        String from = range.minDate() == null ? "any earlier date" : range.minDate().toString();
        return from + " to " + range.maxDate();
    }
}
