package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Offset of an approved expense report's verified amount against a cash advance. Moves no money.
 *
 * <p>{@code idempotencyKey} is optional. A retry with the same key returns the adjustment that the first call
 * created, so a network retry cannot apply the offset twice. Reusing a key for a different adjustment is rejected.
 */
public record CashAdvanceAdjustmentRequest(
        @NotNull UUID advanceId,
        @NotNull UUID reportId,
        @NotNull @Positive BigDecimal adjustedAmount,
        @Size(max = 255) String adjustedBy,
        @Size(max = 255) String idempotencyKey
) {

    /** Request without an idempotency key. */
    public CashAdvanceAdjustmentRequest(UUID advanceId, UUID reportId, BigDecimal adjustedAmount, String adjustedBy) {
        this(advanceId, reportId, adjustedAmount, adjustedBy, null);
    }
}
