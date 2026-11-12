package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Proposal of a carry-forward: part of the source advance's unspent balance moves to a destination advance.
 * {@code idempotencyKey} is optional; a retry with the same key returns the original proposal.
 */
public record CashAdvanceCarryForwardRequest(
        @NotNull UUID sourceAdvanceId,
        @NotNull UUID destinationAdvanceId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 255) String idempotencyKey
) {
}
