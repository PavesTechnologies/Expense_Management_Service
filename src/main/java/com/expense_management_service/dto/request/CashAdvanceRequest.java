package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CashAdvanceRequest(
        @Size(max = 255) String employeeId,
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        BigDecimal baseAmount,
        String purpose,
        String title,
        String notes,
        @Size(max = 255) String status,
        LocalDate settlementDueDate,
        LocalDate neededByDate,
        BigDecimal outstandingBalance
) {
    public CashAdvanceRequest(
            String employeeId,
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            String status,
            LocalDate settlementDueDate,
            BigDecimal outstandingBalance
    ) {
        this(employeeId, amount, currencyId, baseAmount, purpose, null, null, status, settlementDueDate, settlementDueDate, outstandingBalance);
    }

    public CashAdvanceRequest(
            String employeeId,
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            String status,
            LocalDate settlementDueDate,
            LocalDate neededByDate,
            BigDecimal outstandingBalance
    ) {
        this(employeeId, amount, currencyId, baseAmount, purpose, null, null, status, settlementDueDate, neededByDate, outstandingBalance);
    }
}

