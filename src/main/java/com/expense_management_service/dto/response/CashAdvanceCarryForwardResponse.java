package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CashAdvanceCarryForwardResponse(
        UUID carryForwardId,
        UUID sourceAdvanceId,
        UUID destinationAdvanceId,
        String employeeId,
        String currencyCode,
        BigDecimal amount,
        String status,
        String reason,
        String requestedBy,
        LocalDateTime requestedAt,
        String approvedBy,
        LocalDateTime approvedAt,
        LocalDateTime appliedAt,
        String reversedBy,
        LocalDateTime reversedAt
) {
}
