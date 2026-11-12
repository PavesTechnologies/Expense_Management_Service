package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Marks the unused balance of an advance as ready to carry forward to the employee's next approved advance. */
public record CarryForwardReadyRequest(
        @NotNull UUID advanceId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 500) String reason
) {
}
