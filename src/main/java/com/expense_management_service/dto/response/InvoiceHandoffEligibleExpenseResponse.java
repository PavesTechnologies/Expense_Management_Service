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
        /** Base gross - base recoverable tax: what the client is billed before AR's output tax. */
        BigDecimal costBasis,
        BigDecimal recoverableTaxAmount,
        String taxCode,
        /** XMS's internal project UUID. Project fields are null when no project was selected. */
        UUID projectId,
        /** PMS's stable numeric project id - informational; AR matches on {@code resolvedClientId}. */
        Long pmsProjectId,
        /** PMS's projectKey as last refreshed - readable, but mutable in PMS. */
        String projectCode,
        String projectName,
        /** RMS client UUID - the cross-system key AR matches on. Always set for a billable line. */
        UUID resolvedClientId,
        String resolvedClientName,
        int receiptCount
) {
}
