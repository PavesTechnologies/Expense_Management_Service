package com.expense_management_service.dto.response;

import java.time.LocalDate;

/**
 * The dates an expense line on a report may carry, from the same rules the save path enforces.
 *
 * @param minDate       earliest permitted expense date, or null when there is no lower bound
 * @param maxDate       latest permitted expense date (never after {@code today}), or null when nothing is permitted
 * @param today         the business-calendar date the range was computed for
 * @param basis         what the range is taken from, for display (for example "the report's trip dates")
 * @param blockedReason why no expense date is permitted yet, or null when the range applies
 */
public record ExpenseDateRangeResponse(
        LocalDate minDate,
        LocalDate maxDate,
        LocalDate today,
        String basis,
        String blockedReason) {
}
