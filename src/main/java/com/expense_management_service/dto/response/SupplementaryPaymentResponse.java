package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record SupplementaryPaymentResponse(
        UUID paymentId,
        UUID claimId,
        String claimNumber,
        String employeeId,
        BigDecimal amount,
        String currencyCode,
        String status,
        String requestedBy,
        LocalDateTime requestedAt,
        String paidBy,
        LocalDateTime paidAt,
        String bankReference,
        String paymentMethod,
        LocalDate paymentDate
) {
}
