package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One finance-verified, client-billable, not-yet-handed-off expense line item, for the
 * invoice team's handoff queue. See {@code ExpenseLineItemRepository.findEligibleForInvoiceHandoff}
 * for the exact eligibility rule.
 */
public record InvoiceHandoffEligibleExpenseResponse(
        UUID lineItemId,
        UUID reportId,
        String reportNumber,
        String employeeId,
        LocalDate expenseDate,
        String categoryName,
        String description,
        BigDecimal amount,
        String currencyCode,
        BigDecimal baseAmount,
        String baseCurrencyCode,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        UUID projectId,
        String projectCode,
        String projectName,
        UUID resolvedClientId,
        String resolvedClientName,
        int receiptCount
) {
}
