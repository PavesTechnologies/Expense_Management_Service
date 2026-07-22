package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CashAdvanceRequest(
        @NotBlank @Size(max = 255) String employeeId,
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        BigDecimal baseAmount,
        String purpose,
        @Size(max = 255) String status,
        LocalDate settlementDueDate,
        BigDecimal outstandingBalance
) {
}
