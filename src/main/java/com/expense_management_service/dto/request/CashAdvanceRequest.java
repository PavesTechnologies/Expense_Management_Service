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
        LocalDate neededByDate,
        /** Expected start of the trip the advance is for. Prefills the expense report; the actual dates are set on the report. */
        LocalDate expectedTripStartDate,
        /** Expected end of the trip the advance is for. */
        LocalDate expectedTripEndDate,
        /** Null is read as COMPLETE_NEW, the behaviour before request types existed. */
        com.expense_management_service.enums.CashAdvanceRequestType requestType,
        /** Only for ADVANCE_PLUS_OUTSTANDING: the amount needed now, on top of the eligible outstanding balance. */
        BigDecimal additionalAmount,
        /** Required for COMPLETE_NEW when the employee has an outstanding balance. */
        String employeeJustification
) {
    /** The request before request types: no type, no additional amount, no justification. */
    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            UUID costCenterId,
            BigDecimal baseAmount,
            String purpose,
            String title,
            String notes,
            LocalDate settlementDueDate,
            LocalDate neededByDate,
            LocalDate expectedTripStartDate,
            LocalDate expectedTripEndDate
    ) {
        this(amount, currencyId, costCenterId, baseAmount, purpose, title, notes, settlementDueDate, neededByDate,
                expectedTripStartDate, expectedTripEndDate, null, null, null);
    }

    /** The request without the expected trip dates. */
    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            UUID costCenterId,
            BigDecimal baseAmount,
            String purpose,
            String title,
            String notes,
            LocalDate settlementDueDate,
            LocalDate neededByDate
    ) {
        this(amount, currencyId, costCenterId, baseAmount, purpose, title, notes, settlementDueDate, neededByDate, null, null, null, null, null);
    }

    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            LocalDate settlementDueDate
    ) {
        this(amount, currencyId, null, baseAmount, purpose, null, null, settlementDueDate, settlementDueDate, null, null, null, null, null);
    }

    public CashAdvanceRequest(
            BigDecimal amount,
            UUID currencyId,
            BigDecimal baseAmount,
            String purpose,
            LocalDate settlementDueDate,
            LocalDate neededByDate
    ) {
        this(amount, currencyId, null, baseAmount, purpose, null, null, settlementDueDate, neededByDate, null, null, null, null, null);
    }
}

