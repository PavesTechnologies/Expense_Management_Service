package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CashAdvanceRepaymentResponse(
        UUID repaymentId,
        UUID advanceId,
        BigDecimal amount,
        String paymentMethod,
        String paymentReference,
        String repaidBy,
        LocalDateTime repaidAt,
        String notes
) {
}
