package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record CashAdvanceResponse(
        UUID advanceId,
        String employeeId,
        String managerId,
        UUID costCenterId,
        String costCenterName,
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
        LocalDateTime updatedAt,
        String title,
        String notes,
        /** Balance carried in from earlier advances. It is part of {@code amount}, so Finance pays amount minus this. */
        BigDecimal carriedInAmount,
        /** Balance this advance has carried out to later advances (carry-forward). */
        BigDecimal carriedOutAmount,
        LocalDate expectedTripStartDate,
        LocalDate expectedTripEndDate,
        /** Amount frozen at final approval. Finance cannot change it after this. */
        BigDecimal approvedAmount,
        /** Cash actually paid at disbursement. Compare with approvedAmount for the audit. */
        BigDecimal disbursedAmount,
        /** Why the manager or Finance rejected the request. Null unless the status is REJECTED. */
        String rejectionReason,
        /** Why the manager deferred the request. Null unless the status is DEFERRED. */
        String deferredReason,
        /** COMPLETE_NEW or ADVANCE_PLUS_OUTSTANDING. Null on older rows. */
        String requestType,
        BigDecimal additionalAmountRequested,
        BigDecimal totalAdvanceAllocation,
        String employeeJustification
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
            LocalDate neededByDate,
            BigDecimal outstandingBalance,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this(advanceId, employeeId, managerId, null, null, amount, currencyId, currencyCode, baseAmount,
                purpose, status, settlementDueDate, neededByDate, outstandingBalance, createdAt, updatedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

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
        this(advanceId, employeeId, managerId, null, null, amount, currencyId, currencyCode, baseAmount,
            purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, createdAt, updatedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        this(advanceId, employeeId, null, null, null, amount, currencyId, currencyCode, baseAmount,
            purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, createdAt, updatedAt, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        this(advanceId, employeeId, null, null, null, amount, currencyId, currencyCode, baseAmount,
                purpose, status, settlementDueDate, settlementDueDate, outstandingBalance, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
