package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Versioned client-billing payload for AR (design §15) - the authoritative source; events only
 * signal that it is ready. Amounts are in the organization base currency ({@code currency}).
 * AR bills {@code costBasis} and calculates its own output tax on it; the input tax fields are
 * informational, for reconciliation.
 * <p>
 * v2 adds {@code pmsProjectId}, PMS's own project id - the key AR and PMS identify the project by.
 * {@code projectId} is XMS's internal project reference, kept for v1 readers.
 */
public record BillingPayloadResponse(
        int payloadVersion,
        UUID lineItemId,
        UUID reportId,
        String reportNumber,
        String employeeId,
        LocalDate expenseDate,
        String category,
        String description,
        String merchantName,
        /** XMS's internal project reference - not an id PMS or AR know. */
        UUID projectId,
        /** PMS's project id: match the project in PMS/AR by this. */
        Long pmsProjectId,
        String projectCode,
        String projectName,
        UUID clientId,
        String clientName,
        /** gross - recoverable input tax: the company's real cost to bill. */
        BigDecimal costBasis,
        BigDecimal grossAmount,
        BigDecimal inputTaxAmount,
        BigDecimal recoverableTaxAmount,
        String taxCode,
        String currency,
        String originalCurrency,
        BigDecimal originalAmount,
        List<UUID> receiptIds,
        /** PENDING or HANDED_OFF. */
        String handoffStatus,
        String invoiceReference
) {
}
