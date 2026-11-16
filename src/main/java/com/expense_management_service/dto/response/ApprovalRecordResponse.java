package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the approver's merged, read-only record list: an expense report or a cash advance, shown together.
 *
 * @param type       {@code EXPENSE} or {@code CASH_ADVANCE}
 * @param id         the report id or the advance id
 * @param reference  report number, or a short advance reference
 * @param status     the record's own status (report status or advance status)
 * @param date       the date the list sorts on: submission (expenses) or request (advances)
 */
public record ApprovalRecordResponse(
        String type,
        UUID id,
        String reference,
        String employeeId,
        String title,
        String status,
        BigDecimal amount,
        String currencyCode,
        LocalDateTime date
) {
    public static final String EXPENSE = "EXPENSE";
    public static final String CASH_ADVANCE = "CASH_ADVANCE";
}
