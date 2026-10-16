package com.expense_management_service.service.impl;

import java.time.LocalDate;

/**
 * Submission deadline for expense reports: the estimated trip end date plus {@code expense.submission.deadline-days}
 * calendar days. A report may be submitted on the deadline date itself and is overdue from the day after it.
 */
public final class SubmissionDeadlinePolicy {

    public static final int DEFAULT_DEADLINE_DAYS = 30;

    private SubmissionDeadlinePolicy() {
    }

    public static int requireValidDays(int deadlineDays) {
        if (deadlineDays < 1) {
            throw new IllegalArgumentException("expense.submission.deadline-days must be at least 1, was " + deadlineDays);
        }
        return deadlineDays;
    }

    /**
     * The actual reporting period may not end in the future and may not start after it ends. Past periods are allowed;
     * they still go through the submission deadline. Deadline extensions and reopens do not change this rule.
     */
    public static boolean isValidReportingPeriod(LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd, LocalDate today) {
        if (reportingPeriodEnd != null && reportingPeriodEnd.isAfter(today)) {
            return false;
        }
        return reportingPeriodStart == null || reportingPeriodEnd == null || !reportingPeriodEnd.isBefore(reportingPeriodStart);
    }

    /**
     * Submit-time rule for the reporting period: both dates are required, the start may not be after the end, and the
     * end may not be after today. Returns the error message, or null when the period is complete and valid. Drafts are
     * not checked here. Finance extensions and reopens never change this rule.
     */
    public static String reportingPeriodSubmitError(LocalDate reportingPeriodStart, LocalDate reportingPeriodEnd, LocalDate today) {
        if (reportingPeriodStart == null || reportingPeriodEnd == null) {
            return "Enter the reporting period start and end dates before submitting this report.";
        }
        if (reportingPeriodEnd.isAfter(today)) {
            return "The reporting period cannot end in the future.";
        }
        if (reportingPeriodStart.isAfter(reportingPeriodEnd)) {
            return "The reporting period start date cannot be after its end date.";
        }
        return null;
    }

    /** The trip end date may not fall before the start date. Either date may be absent while the report is a draft. */
    public static boolean isValidTripRange(LocalDate tripStartDate, LocalDate tripEndDate) {
        return tripStartDate == null || tripEndDate == null || !tripEndDate.isBefore(tripStartDate);
    }

    /** The last date a report may be submitted, or null when the trip end date is not known. */
    public static LocalDate deadline(LocalDate tripEndDate, int deadlineDays) {
        if (tripEndDate == null) {
            return null;
        }
        return tripEndDate.plusDays(requireValidDays(deadlineDays));
    }

    /**
     * The deadline that applies to a report, or null when no clock is running.
     * <ul>
     *   <li>Finance extension: the extension date replaces the computed deadline.</li>
     *   <li>Advance-linked report: advance disbursement date + days. No clock until the advance is disbursed.</li>
     *   <li>Regular report: trip end date + days.</li>
     * </ul>
     */
    public static LocalDate effectiveDeadline(LocalDate tripEndDate, boolean linkedToAdvance,
                                              LocalDate advanceDisbursementDate, LocalDate extendedUntil, int deadlineDays) {
        if (extendedUntil != null) {
            return extendedUntil;
        }
        if (linkedToAdvance) {
            return advanceDisbursementDate == null ? null : deadline(advanceDisbursementDate, deadlineDays);
        }
        return deadline(tripEndDate, deadlineDays);
    }

    /** True when {@code today} is after the effective deadline. A Finance reopen keeps the window open regardless of date. */
    public static boolean isOverdue(LocalDate tripEndDate, boolean linkedToAdvance, LocalDate advanceDisbursementDate,
                                    LocalDate extendedUntil, boolean reopened, int deadlineDays, LocalDate today) {
        if (reopened) {
            return false;
        }
        LocalDate effective = effectiveDeadline(tripEndDate, linkedToAdvance, advanceDisbursementDate, extendedUntil, deadlineDays);
        return effective != null && today.isAfter(effective);
    }

    /** Regular report (no advance link): trip end date based. */
    public static boolean isOverdue(LocalDate tripEndDate, int deadlineDays, LocalDate extendedUntil,
                                    boolean reopened, LocalDate today) {
        return isOverdue(tripEndDate, false, null, extendedUntil, reopened, deadlineDays, today);
    }
}
