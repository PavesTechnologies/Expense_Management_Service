package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ExpenseDateRangeResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Expense-date rules, with "today" fixed so every case is an exact calendar date. Boundaries are inclusive. */
class ExpenseDatePolicyTest {

    private static final int WINDOW_DAYS = 30;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private static ExpenseReport standalone(LocalDate tripStart, LocalDate tripEnd) {
        ExpenseReport report = new ExpenseReport();
        report.setTripStartDate(tripStart);
        report.setTripEndDate(tripEnd);
        return report;
    }

    private static ExpenseReport linked(LocalDateTime disbursedAt, LocalDate tripStart, LocalDate tripEnd) {
        ExpenseReport report = standalone(tripStart, tripEnd);
        report.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).disbursedAt(disbursedAt).build());
        return report;
    }

    private static ExpenseDateRangeResponse rangeOn(ExpenseReport report, LocalDate today) {
        return ExpenseDatePolicy.range(report, today, WINDOW_DAYS);
    }

    private static void assertAccepted(ExpenseDateRangeResponse range, LocalDate date) {
        ExpenseDatePolicy.assertAllowed(range, date);
    }

    private static void assertRejected(ExpenseDateRangeResponse range, LocalDate date, String message) {
        assertThatThrownBy(() -> ExpenseDatePolicy.assertAllowed(range, date))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    // ---- normal report: trip dates, no future dates ----

    @Test
    void normal_acceptsAPastDateInsideTheTrip() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), TODAY);

        assertAccepted(range, LocalDate.of(2026, 10, 25));
    }

    @Test
    void normal_acceptsToday_whenTodayIsInsideTheTrip() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 11, 25)), TODAY);

        assertAccepted(range, TODAY);
    }

    @Test
    void normal_rejectsAFutureDateOutsideTheTrip() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), TODAY);

        assertRejected(range, LocalDate.of(2026, 10, 29), "cannot be in the future");
    }

    @Test
    void normal_rejectsADateBeforeTheTripStart() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), TODAY);

        assertRejected(range, LocalDate.of(2026, 10, 18),
                "must fall within the report's trip dates (2026-10-20 to 2026-10-27)");
    }

    @Test
    void normal_rejectsADateAfterTheTripEnd() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), TODAY);

        assertRejected(range, LocalDate.of(2026, 10, 28), "must fall within the report's trip dates");
    }

    @Test
    void normal_boundariesAreInclusive() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)), TODAY);

        assertAccepted(range, LocalDate.of(2026, 10, 20));
        assertAccepted(range, LocalDate.of(2026, 10, 27));
    }

    @Test
    void normal_permitsNoDateWhileTheTripHasNotStarted() {
        ExpenseDateRangeResponse range = rangeOn(standalone(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 5)), TODAY);

        assertRejected(range, LocalDate.of(2026, 10, 27), "The trip starts on 2026-10-31");
    }

    // ---- cash-advance-linked report: trip dates AND spending window, no future dates ----

    @Test
    void linked_acceptsAPastDateInsideTheTripAndTheWindow() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 20, 10, 0), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));

        assertAccepted(rangeOn(report, TODAY), LocalDate.of(2026, 10, 25));
    }

    @Test
    void linked_rejectsAFutureDateEvenInsideTheTripAndWindow() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 28, 10, 0), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 5));
        LocalDate later = LocalDate.of(2026, 11, 3);

        ExpenseDateRangeResponse range = rangeOn(report, later);
        assertAccepted(range, LocalDate.of(2026, 11, 2));
        assertRejected(rangeOn(report, TODAY), LocalDate.of(2026, 11, 2), "The trip starts on 2026-10-31");
        assertRejected(range, LocalDate.of(2026, 11, 4), "cannot be in the future");
    }

    @Test
    void linked_acceptsADateInsideTheTripAndWindowOnceTheTripHasStarted() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 28, 10, 0), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 5));

        assertAccepted(rangeOn(report, LocalDate.of(2026, 11, 3)), LocalDate.of(2026, 11, 2));
    }

    @Test
    void linked_rejectsADateBeforeTheTripStart() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 15, 10, 0), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));

        assertRejected(rangeOn(report, TODAY), LocalDate.of(2026, 10, 18),
                "must fall within the report's trip dates and the cash advance expense deadline (2026-10-20 to 2026-10-27)");
    }

    @Test
    void linked_rejectsADateAfterTheTripEnd() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 20, 10, 0), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));

        assertRejected(rangeOn(report, TODAY), LocalDate.of(2026, 10, 28), "must fall within the report's trip dates");
    }

    @Test
    void linked_allowsADateBeforeTheDisbursement_becauseCashOftenArrivesAfterTheTrip() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 20, 10, 0), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 27));

        assertAccepted(rangeOn(report, TODAY), LocalDate.of(2026, 10, 18));
    }

    @Test
    void linked_lateDisbursementStillGivesTheWholeTrip() {
        // Trip 2-28 Oct, cash paid 30 Oct. The old rule left no date open; the trip dates now govern.
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 30, 9, 0), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 28));
        ExpenseDateRangeResponse range = rangeOn(report, LocalDate.of(2026, 11, 2));

        assertAccepted(range, LocalDate.of(2026, 10, 2));
        assertAccepted(range, LocalDate.of(2026, 10, 28));
        assertRejected(range, LocalDate.of(2026, 10, 29), "must fall within");
    }
    @Test
    void linked_boundariesAreInclusive() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 20, 23, 59), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));
        ExpenseDateRangeResponse range = rangeOn(report, TODAY);

        assertAccepted(range, LocalDate.of(2026, 10, 20));
        assertAccepted(range, LocalDate.of(2026, 10, 27));
    }

    @Test
    void linked_latestDateIsTheTripEnd_evenWhenTheTripIsOverAndCashIsLate() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 1, 10, 0), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20));
        ExpenseDateRangeResponse range = rangeOn(report, LocalDate.of(2026, 11, 2));

        assertAccepted(range, LocalDate.of(2026, 10, 20));
        assertRejected(range, LocalDate.of(2026, 10, 21), "must fall within");
    }
    @Test
    void linked_reopenedWindowHasNoEnd() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 1, 10, 0), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 10));
        report.getCashAdvance().setExpenseDeadlineReopened(true);

        assertAccepted(rangeOn(report, LocalDate.of(2026, 11, 2)), LocalDate.of(2026, 11, 1));
    }

    @Test
    void linked_financeExtensionReplacesTheWindowEnd() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 1, 10, 0), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 10));
        report.getCashAdvance().setExpenseDeadlineExtendedUntil(LocalDate.of(2026, 11, 5));
        ExpenseDateRangeResponse range = rangeOn(report, LocalDate.of(2026, 11, 6));

        assertAccepted(range, LocalDate.of(2026, 11, 5));
        assertRejected(range, LocalDate.of(2026, 11, 6), "must fall within");
    }

    @Test
    void linked_undisbursedAdvanceDoesNotBlockTheTrip() {
        ExpenseReport report = linked(null, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27));

        assertAccepted(rangeOn(report, TODAY), LocalDate.of(2026, 10, 25));
    }
    @Test
    void linked_withoutTripDatesAndNoDisbursement_stillCapsAtToday() {
        ExpenseReport report = linked(null, null, null);
        ExpenseDateRangeResponse range = rangeOn(report, TODAY);

        assertAccepted(range, TODAY);
        assertRejected(range, LocalDate.of(2026, 10, 29), "cannot be in the future");
    }
    @Test
    void linked_withoutTripDatesIsBoundedByTheWindowAndToday() {
        ExpenseReport report = linked(LocalDateTime.of(2026, 10, 1, 10, 0), null, null);
        ExpenseDateRangeResponse range = rangeOn(report, TODAY);

        assertAccepted(range, TODAY);
        assertRejected(range, LocalDate.of(2026, 10, 29), "cannot be in the future");
    }
}
