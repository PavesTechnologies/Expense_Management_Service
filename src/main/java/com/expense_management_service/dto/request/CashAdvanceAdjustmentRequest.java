package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CashAdvanceAdjustmentRequest(
        @NotNull UUID advanceId,
        @NotNull UUID reportId,
        @NotNull @Positive BigDecimal adjustedAmount,
        @Size(max = 255) String adjustedBy
) {
}
