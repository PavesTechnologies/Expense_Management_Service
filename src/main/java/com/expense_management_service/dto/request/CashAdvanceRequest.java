package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CashAdvanceRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        UUID costCenterId,
        BigDecimal baseAmount,
        String purpose,
        String title,
        String notes,
        LocalDate settlementDueDate,
        LocalDate neededByDate
) {
    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            LocalDate settlementDueDate
    ) {
        this(amount, currencyId, null, baseAmount, purpose, null, null, settlementDueDate, settlementDueDate);
    }

    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            LocalDate settlementDueDate,
            LocalDate neededByDate
    ) {
        this(amount, currencyId, null, baseAmount, purpose, null, null, settlementDueDate, neededByDate);
    }
}

