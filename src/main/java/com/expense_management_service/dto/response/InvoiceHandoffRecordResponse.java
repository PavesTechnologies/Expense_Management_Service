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
        String projectCode,
        String projectName,
        String resolvedClientName,
        String invoiceReference,
        LocalDateTime handedOffAt,
        String remarks
) {
}
