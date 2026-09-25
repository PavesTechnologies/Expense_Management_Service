package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CashAdvanceRepaymentRequest(
        @NotNull UUID advanceId,
        @NotNull @Positive BigDecimal amount,
        @Size(max = 255) String paymentMethod,
        @Size(max = 255) String paymentReference,
        String notes
) {
}
