package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseLineItemResponse(
        UUID lineItemId,
        UUID reportId,
        String reportNumber,
        UUID categoryId,
        String categoryName,
        LocalDate expenseDate,
        String merchantName,
        String description,
        BigDecimal amount,
        UUID currencyId,
        String currencyCode,
        BigDecimal exchangeRate,
        BigDecimal baseAmount,
        BigDecimal taxAmount,
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
