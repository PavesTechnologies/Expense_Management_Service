package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseCategoryResponse(
        UUID categoryId,
        String categoryCode,
        String categoryName,
        UUID glAccountId,
        String glAccountName,
        String description,
        Boolean receiptRequired,
        BigDecimal maxLimit,
        String taxCode,
        /** Rate (%) of {@code taxCode} if it is active and in effect today, else null — pre-fills GST on line items. */
        BigDecimal taxRate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
