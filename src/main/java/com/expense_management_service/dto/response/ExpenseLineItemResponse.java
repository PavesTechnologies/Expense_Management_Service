package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseLineItemResponse(
        UUID lineItemId,
        UUID reportId,
        String reportNumber,
        String reportStatus,
        UUID categoryId,
        String categoryName,
        boolean categoryActive,
        boolean receiptRequired,
        BigDecimal categoryMaxLimit,
        LocalDate expenseDate,
        String merchantName,
        String description,
        BigDecimal amount,
        UUID currencyId,
        String currencyCode,
        BigDecimal exchangeRate,
        BigDecimal baseAmount,
        /** ISO code of the currency {@code baseAmount} is denominated in — the Organization Base Currency, not the report's own currency. */
        String baseCurrencyCode,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        UUID costCenterId,
        String costCenterName,
        UUID projectId,
        String projectName,
        Boolean clientBillable,
        String lineStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
