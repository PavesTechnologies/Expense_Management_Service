package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CashAdvance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CashAdvanceExpenseWindowTest {

    private static final LocalDateTime DISBURSED = LocalDateTime.of(2026, 10, 1, 10, 0);

    private static CashAdvance disbursed() {
        return CashAdvance.builder().disbursedAt(DISBURSED).build();
    }

    @Test
    void notStartedWindowIsNeverLocked() {
        CashAdvance advance = CashAdvance.builder().build();

        assertNull(CashAdvanceExpenseWindow.deadline(advance));
        assertFalse(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2030, 1, 1, 0, 0)));
        assertEquals(0, CashAdvanceExpenseWindow.daysLeft(advance, LocalDateTime.of(2026, 10, 2, 0, 0)));
    }

    @Test
    void deadlineIsExactlyThirtyDaysAfterDisbursement() {
        assertEquals(LocalDateTime.of(2026, 10, 31, 10, 0), CashAdvanceExpenseWindow.deadline(disbursed()));
    }

    @Test
    void submissionAtTheDeadlineInstantIsStillAllowed() {
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 31, 10, 0);

        assertFalse(CashAdvanceExpenseWindow.isLocked(disbursed(), deadline));
    }

    @Test
    void submissionOneSecondAfterTheDeadlineIsLocked() {
        LocalDateTime justAfter = LocalDateTime.of(2026, 10, 31, 10, 0, 1);

        assertTrue(CashAdvanceExpenseWindow.isLocked(disbursed(), justAfter));
    }

    @Test
    void configuredWindowLengthChangesTheDeadline() {
        CashAdvance advance = disbursed();

        assertEquals(LocalDateTime.of(2026, 11, 15, 10, 0), CashAdvanceExpenseWindow.deadline(advance, 45));
        assertFalse(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2026, 11, 1, 9, 0), 45));
        assertTrue(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2026, 11, 16, 0, 0), 45));
    }

    @Test
    void zeroOrNegativeWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CashAdvanceExpenseWindow.deadline(disbursed(), 0));
        assertThrows(IllegalArgumentException.class, () -> CashAdvanceExpenseWindow.isLocked(disbursed(), DISBURSED, -5));
    }

    @Test
    void financeExtensionReplacesTheWindowAndRunsToEndOfDay() {
        CashAdvance advance = CashAdvance.builder()
                .disbursedAt(DISBURSED)
                .expenseDeadlineExtendedUntil(LocalDate.of(2026, 11, 10))
                .build();

        assertEquals(LocalDateTime.of(2026, 11, 10, 23, 59, 59), CashAdvanceExpenseWindow.deadline(advance));
        assertFalse(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2026, 11, 10, 23, 59, 59)));
        assertTrue(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2026, 11, 11, 0, 0)));
    }

    @Test
    void financeReopenKeepsWindowOpenPastTheDeadline() {
        CashAdvance advance = CashAdvance.builder()
                .disbursedAt(DISBURSED)
                .expenseDeadlineReopened(true)
                .build();

        assertFalse(CashAdvanceExpenseWindow.isLocked(advance, LocalDateTime.of(2027, 1, 1, 0, 0)));
    }

    @Test
    void daysLeftCountsWholeDaysBeforeTheDeadline() {
        assertEquals(2, CashAdvanceExpenseWindow.daysLeft(disbursed(), LocalDateTime.of(2026, 10, 29, 10, 0)));
        assertEquals(0, CashAdvanceExpenseWindow.daysLeft(disbursed(), LocalDateTime.of(2026, 11, 1, 10, 0)));
    }

    @Test
    void lockedMessageStatesTheWindowAndDate() {
        String message = CashAdvanceExpenseWindow.lockedMessage(disbursed());

        assertTrue(message.contains("30-day"));
        assertTrue(message.contains("2026-10-31"));
    }
}
