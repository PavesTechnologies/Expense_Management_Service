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
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
