package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One completed handoff to the invoice team (an {@code InvoiceSync} row with status HANDED_OFF),
 * with the expense it covers. A reprocessed line item appears once per handoff; {@code remarks}
 * carries the reprocess reason.
 */
public record InvoiceHandoffRecordResponse(
        UUID syncId,
        UUID lineItemId,
        UUID reportId,
        String reportNumber,
        String employeeId,
        LocalDate expenseDate,
        String categoryName,
        String description,
        BigDecimal baseAmount,
        String baseCurrencyCode,
        /** Base gross - base recoverable tax: what the client was billed before AR's output tax (same as the billing payload). */
        BigDecimal costBasis,
        BigDecimal recoverableTaxAmount,
        String taxCode,
        /** PMS's stable numeric project id - informational, null when no project was selected; AR matches on {@code resolvedClientId}. */
        Long pmsProjectId,
        /** PMS's projectKey as last refreshed - readable, but mutable in PMS. */
        String projectCode,
        String projectName,
        /** RMS client UUID - the cross-system key AR matches on. */
        UUID resolvedClientId,
        String resolvedClientName,
        String invoiceReference,
        LocalDateTime handedOffAt,
        String remarks,
        /** PENDING, SENT or FAILED; null for a handoff recorded before delivery to AR existed (AR pulled it). */
        String arDeliveryStatus,
        Integer arDeliveryAttempts,
        LocalDateTime arDeliveredAt,
        String arDeliveryError
) {
    /** Shape before delivery to AR was tracked. */
    public InvoiceHandoffRecordResponse(UUID syncId, UUID lineItemId, UUID reportId, String reportNumber, String employeeId,
                                        LocalDate expenseDate, String categoryName, String description, BigDecimal baseAmount,
                                        String baseCurrencyCode, BigDecimal costBasis, BigDecimal recoverableTaxAmount,
                                        String taxCode, Long pmsProjectId, String projectCode, String projectName,
                                        UUID resolvedClientId, String resolvedClientName, String invoiceReference,
                                        LocalDateTime handedOffAt, String remarks) {
        this(syncId, lineItemId, reportId, reportNumber, employeeId, expenseDate, categoryName, description, baseAmount,
                baseCurrencyCode, costBasis, recoverableTaxAmount, taxCode, pmsProjectId, projectCode, projectName,
                resolvedClientId, resolvedClientName, invoiceReference, handedOffAt, remarks, null, null, null, null);
    }
}
