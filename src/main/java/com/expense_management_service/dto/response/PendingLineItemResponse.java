package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record PendingLineItemResponse(
        UUID lineItemId,
        UUID reviewId,
        String categoryName,
        BigDecimal amount,
        int policyViolationCount
) {
}
