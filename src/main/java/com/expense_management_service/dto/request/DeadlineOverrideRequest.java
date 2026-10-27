package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Finance override of a report's submission deadline. {@code reason} is required and is stored in the audit log.
 * {@code extendedUntil} is required for EXTEND and must be empty for REOPEN.
 */
public record DeadlineOverrideRequest(
        @NotNull DeadlineOverrideAction action,
        LocalDate extendedUntil,
        @NotBlank @Size(max = 500) String reason
) {

    public enum DeadlineOverrideAction {
        EXTEND,
        REOPEN
    }
}
