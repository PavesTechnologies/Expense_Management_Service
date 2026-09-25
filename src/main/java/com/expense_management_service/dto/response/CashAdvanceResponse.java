package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record CashAdvanceResponse(
        UUID advanceId,
        String employeeId,
        String managerId,
        BigDecimal amount,
        UUID currencyId,
        String currencyCode,
        BigDecimal baseAmount,
        String purpose,
        String status,
        LocalDate settlementDueDate,
        LocalDate neededByDate,
        BigDecimal outstandingBalance,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public CashAdvanceResponse(
            UUID advanceId,
            String employeeId,
            String managerId,
            BigDecimal amount,
            UUID currencyId,
            String currencyCode,
            BigDecimal baseAmount,
            String purpose,
            String status,
            LocalDate settlementDueDate,
            BigDecimal outstandingBalance,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(advanceId, employeeId, managerId, amount, currencyId, currencyCode, baseAmount, purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, createdAt, updatedAt);
    }

    public CashAdvanceResponse(
            UUID advanceId,
            String employeeId,
            BigDecimal amount,
            UUID currencyId,
            String currencyCode,
            BigDecimal baseAmount,
            String purpose,
            String status,
            LocalDate settlementDueDate,
            BigDecimal outstandingBalance,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(advanceId, employeeId, null, amount, currencyId, currencyCode, baseAmount, purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, createdAt, updatedAt);
    }

    public CashAdvanceResponse(
            UUID advanceId,
            String employeeId,
            BigDecimal amount,
            UUID currencyId,
            String currencyCode,
            BigDecimal baseAmount,
            String purpose,
            String status,
            LocalDate settlementDueDate,
            BigDecimal outstandingBalance
    ) {
        this(advanceId, employeeId, null, amount, currencyId, currencyCode, baseAmount, purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, null, null);
    }
}

