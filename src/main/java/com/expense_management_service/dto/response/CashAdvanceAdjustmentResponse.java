package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CashAdvanceAdjustmentResponse(
        UUID adjustmentId,
        UUID advanceId,
        UUID reportId,
        BigDecimal adjustedAmount,
        String adjustedBy,
        LocalDateTime adjustedAt
) {
}
