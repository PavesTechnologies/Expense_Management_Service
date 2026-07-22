package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseLineItemRequest(
        @NotNull UUID reportId,
        @NotNull UUID categoryId,
        @NotNull LocalDate expenseDate,
        @Size(max = 255) String merchantName,
        String description,
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        BigDecimal exchangeRate,
        BigDecimal baseAmount,
        BigDecimal taxAmount,
        UUID costCenterId,
        UUID projectId,
        Boolean clientBillable,
        @Size(max = 255) String lineStatus
) {
}
