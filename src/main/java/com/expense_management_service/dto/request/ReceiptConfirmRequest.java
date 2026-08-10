package com.expense_management_service.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Finalizes a reviewed receipt into a real expense line item.
 * <p>
 * If {@code lineItemId} is provided, the receipt is linked to that existing line item instead of
 * creating a new one (manual-entry-first flow) — the response then reports whether its amount
 * differs from what OCR extracted. Otherwise a new line item is created from whichever of these
 * fields are supplied, falling back to the receipt's latest OCR result for anything left null.
 * {@code categoryId} is always employee-supplied — OCR never determines it.
 */
public record ReceiptConfirmRequest(
        UUID lineItemId,
        UUID categoryId,
        LocalDate expenseDate,
        String merchantName,
        String description,
        BigDecimal amount,
        UUID currencyId,
        BigDecimal taxAmount,
        UUID costCenterId,
        UUID projectId,
        Boolean clientBillable
) {
}
