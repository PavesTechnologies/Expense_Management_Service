package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SupplementaryClaimResponse(
        UUID claimId,
        String claimNumber,
        UUID reportId,
        String reportNumber,
        /** Start and end of the original report's trip or event period. Expense dates must fall inside it. */
        LocalDate reportTripStart,
        LocalDate reportTripEnd,
        String employeeId,
        String createdBy,
        boolean createdOnBehalf,
        String creationReason,
        LocalDateTime employeeConfirmedAt,
        String lateReasonCode,
        String lateReasonText,
        BigDecimal totalAmount,
        String currencyCode,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime settledAt,
        LocalDateTime createdAt,
        /** NONE before Finance approval, then REQUESTED, then PAID once AP confirms the payment. */
        String paymentStatus,
        String paymentReference,
        /** Set against this cash advance (carried-forward balance), and how much; the rest is paid by AP. */
        UUID settledAdvanceId,
        BigDecimal advanceOffsetAmount,
        List<SupplementaryClaimItemResponse> items,
        List<SupplementaryClaimHistoryResponse> history
) {
}
