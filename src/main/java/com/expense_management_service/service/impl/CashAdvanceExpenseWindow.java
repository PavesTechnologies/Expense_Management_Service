package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The 30-day window in which an employee must submit the expense report for a disbursed cash advance.
 * <ul>
 *   <li>The window starts at {@code disbursedAt}. If {@code disbursedAt} is empty, the clock has not started and the window is open.</li>
 *   <li>Finance can extend the deadline to a date ({@code expenseDeadlineExtendedUntil}), which replaces the 30 days.</li>
 *   <li>Finance can reopen the window ({@code expenseDeadlineReopened}), which keeps it open regardless of date.</li>
 * </ul>
 * When the window has ended, the advance is locked: no new expense report can be linked to it.
 */
public final class CashAdvanceExpenseWindow {

    public static final int WINDOW_DAYS = 30;
    public static final int REMINDER_DAYS_BEFORE_DEADLINE = 7;

    private CashAdvanceExpenseWindow() {
    }

    /** The last moment expenses may be submitted, or null when the clock has not started. */
    public static LocalDateTime deadline(CashAdvance advance) {
        return deadline(advance, WINDOW_DAYS);
    }

    /** As {@link #deadline(CashAdvance)}, with the window length taken from configuration. */
    public static LocalDateTime deadline(CashAdvance advance, int windowDays) {
        if (windowDays < 1) {
            throw new IllegalArgumentException("expense.submission.deadline-days must be at least 1");
        }
        if (advance.getDisbursedAt() == null) {
            return null;
        }
        if (advance.getExpenseDeadlineExtendedUntil() != null) {
            return advance.getExpenseDeadlineExtendedUntil().atTime(LocalTime.of(23, 59, 59));
        }
        return advance.getDisbursedAt().plusDays(windowDays);
    }

    /** True when the window has ended and Finance has not reopened it. */
    public static boolean isLocked(CashAdvance advance, LocalDateTime now) {
        return isLocked(advance, now, WINDOW_DAYS);
    }

    /** As {@link #isLocked(CashAdvance, LocalDateTime)}, with the window length taken from configuration. */
    public static boolean isLocked(CashAdvance advance, LocalDateTime now, int windowDays) {
        if (advance.isExpenseDeadlineReopened()) {
            return false;
        }
        LocalDateTime deadline = deadline(advance, windowDays);
        return deadline != null && now.isAfter(deadline);
    }

    /** Whole days left before the deadline (0 when already past or not started). */
    public static long daysLeft(CashAdvance advance, LocalDateTime now) {
        LocalDateTime deadline = deadline(advance);
        if (deadline == null || now.isAfter(deadline)) {
            return 0;
        }
        return Duration.between(now, deadline).toDays();
    }

    /** The date the reminder should go out: 7 days before the deadline. */
    public static LocalDateTime reminderDue(CashAdvance advance) {
        LocalDateTime deadline = deadline(advance);
        return deadline == null ? null : deadline.minusDays(REMINDER_DAYS_BEFORE_DEADLINE);
    }

    /** Short message for a disabled button, explaining why it is locked. */
    public static String lockedMessage(CashAdvance advance) {
        LocalDateTime deadline = deadline(advance);
        LocalDate date = deadline == null ? null : deadline.toLocalDate();
        return "The " + WINDOW_DAYS + "-day expense window for this advance ended"
                + (date == null ? "" : " on " + date)
                + ". Ask Finance to extend or reopen it.";
    }
}
