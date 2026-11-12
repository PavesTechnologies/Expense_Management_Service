package com.expense_management_service.dto.response;

import java.time.LocalDate;
import java.util.UUID;

/** The report's deadline after an override. {@code submissionDeadline} is null when a reopen lifts the check. */
public record DeadlineOverrideResponse(
        UUID reportId,
        String action,
        LocalDate extendedUntil,
        boolean reopened,
        LocalDate submissionDeadline,
        String performedBy
) {
}
