package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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
        /** Frozen at submission time — see {@code ExpenseLineItem.resolvedClientId}'s javadoc. Null for non-billable line items. */
        UUID resolvedClientId,
        String resolvedClientName,
        Boolean clientBillable,
        String lineStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PolicyWarningResponse> policyWarnings,
        /** The tax snapshot (code, components, source, status); null only for hand-built test fixtures. */
        LineTaxResponse tax
) {
}
