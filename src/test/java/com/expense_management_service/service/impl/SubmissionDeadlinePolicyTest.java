package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionDeadlinePolicyTest {

    private static final LocalDate TRIP_END = LocalDate.of(2026, 10, 1);

    @Test
    void deadlineIsTripEndPlusConfiguredDays() {
        assertEquals(LocalDate.of(2026, 10, 31), SubmissionDeadlinePolicy.deadline(TRIP_END, 30));
        assertEquals(LocalDate.of(2026, 11, 15), SubmissionDeadlinePolicy.deadline(TRIP_END, 45));
    }

    @Test
    void deadlineIsNullWithoutATripEndDate() {
        assertNull(SubmissionDeadlinePolicy.deadline(null, 30));
    }

    @Test
    void submissionOnTheDeadlineDateIsAllowed() {
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, null, false, LocalDate.of(2026, 10, 31)));
    }

    @Test
    void submissionTheDayAfterTheDeadlineIsOverdue() {
        assertTrue(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, null, false, LocalDate.of(2026, 11, 1)));
    }

    @Test
    void submissionBeforeTheTripEndIsAllowed() {
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, null, false, LocalDate.of(2026, 9, 1)));
    }

    @Test
    void financeExtensionReplacesTheComputedDeadline() {
        LocalDate extendedUntil = LocalDate.of(2026, 11, 10);

        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, extendedUntil, false, LocalDate.of(2026, 11, 10)));
        assertTrue(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, extendedUntil, false, LocalDate.of(2026, 11, 11)));
    }

    @Test
    void financeReopenLiftsTheCheckEntirely() {
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, 30, null, true, LocalDate.of(2030, 1, 1)));
    }

    @Test
    void missingTripEndWithoutAnExtensionIsNotOverdueHere() {
        // The service rejects a missing trip end date before this check; the policy itself has nothing to compare.
        assertFalse(SubmissionDeadlinePolicy.isOverdue(null, 30, null, false, LocalDate.of(2026, 10, 1)));
    }

    @Test
    void tripEndSameDayAsStartIsValid() {
        assertTrue(SubmissionDeadlinePolicy.isValidTripRange(TRIP_END, TRIP_END));
    }

    @Test
    void tripEndBeforeStartIsRejected() {
        assertFalse(SubmissionDeadlinePolicy.isValidTripRange(TRIP_END, TRIP_END.minusDays(1)));
    }

    @Test
    void missingTripDatesDoNotFailTheRangeRuleWhileDrafting() {
        assertTrue(SubmissionDeadlinePolicy.isValidTripRange(null, TRIP_END));
        assertTrue(SubmissionDeadlinePolicy.isValidTripRange(TRIP_END, null));
        assertTrue(SubmissionDeadlinePolicy.isValidTripRange(null, null));
    }

    @Test
    void advanceLinkedReportUsesDisbursementDateNotTripEnd() {
        LocalDate disbursed = LocalDate.of(2026, 10, 1);
        assertEquals(LocalDate.of(2026, 10, 31),
                SubmissionDeadlinePolicy.effectiveDeadline(TRIP_END, true, disbursed, null, 30));
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, true, disbursed, null, false, 30, LocalDate.of(2026, 10, 31)));
        assertTrue(SubmissionDeadlinePolicy.isOverdue(TRIP_END, true, disbursed, null, false, 30, LocalDate.of(2026, 11, 1)));
    }

    @Test
    void advanceLinkedReportHasNoClockBeforeDisbursement() {
        assertNull(SubmissionDeadlinePolicy.effectiveDeadline(TRIP_END, true, null, null, 30));
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, true, null, null, false, 30, LocalDate.of(2030, 1, 1)));
    }

    @Test
    void advanceLinkedExtensionAndReopenStillOverride() {
        LocalDate disbursed = LocalDate.of(2026, 10, 1);
        LocalDate extended = LocalDate.of(2026, 12, 1);
        assertEquals(extended, SubmissionDeadlinePolicy.effectiveDeadline(TRIP_END, true, disbursed, extended, 30));
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, true, disbursed, extended, false, 30, LocalDate.of(2026, 12, 1)));
        assertFalse(SubmissionDeadlinePolicy.isOverdue(TRIP_END, true, disbursed, null, true, 30, LocalDate.of(2030, 1, 1)));
    }

    @Test
    void reportingPeriodEndingTodayOrEarlierIsValid() {
        LocalDate today = LocalDate.of(2026, 10, 16);
        assertTrue(SubmissionDeadlinePolicy.isValidReportingPeriod(LocalDate.of(2026, 9, 1), today, today));
        assertTrue(SubmissionDeadlinePolicy.isValidReportingPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), today));
        assertTrue(SubmissionDeadlinePolicy.isValidReportingPeriod(null, null, today));
    }

    @Test
    void reportingPeriodEndingInTheFutureIsRejected() {
        LocalDate today = LocalDate.of(2026, 10, 16);
        assertFalse(SubmissionDeadlinePolicy.isValidReportingPeriod(null, today.plusDays(1), today));
        assertFalse(SubmissionDeadlinePolicy.isValidReportingPeriod(today, today.plusMonths(3), today));
    }

    @Test
    void reportingPeriodEndingBeforeItStartsIsRejected() {
        LocalDate today = LocalDate.of(2026, 10, 16);
        assertFalse(SubmissionDeadlinePolicy.isValidReportingPeriod(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 1), today));
    }

    @Test
    void invalidDeadlineDaysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SubmissionDeadlinePolicy.requireValidDays(0));
        assertThrows(IllegalArgumentException.class, () -> SubmissionDeadlinePolicy.requireValidDays(-3));
        assertThrows(IllegalArgumentException.class, () -> SubmissionDeadlinePolicy.deadline(TRIP_END, 0));
        assertEquals(30, SubmissionDeadlinePolicy.requireValidDays(30));
    }
}
