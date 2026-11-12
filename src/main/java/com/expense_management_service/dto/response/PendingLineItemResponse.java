package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Full review context for one line item - one screen, no tab-switching to see the receipt/violations separately (§14 backend gaps). */
public record PendingLineItemResponse(
        UUID lineItemId,
        UUID reviewId,
        String categoryName,
        String merchantName,
        String description,
        LocalDate expenseDate,
        BigDecimal amount,
        String currencyCode,
        List<PolicyWarningResponse> policyViolations
) {
}
