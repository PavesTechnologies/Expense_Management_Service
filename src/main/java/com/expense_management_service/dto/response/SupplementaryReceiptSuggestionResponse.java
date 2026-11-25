package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What OCR read from a receipt, offered to fill a supplementary expense. {@code ocrStatus} is PROCESSING until OCR
 * finishes; the values are null until then, and the employee can still type them in.
 */
public record SupplementaryReceiptSuggestionResponse(
        UUID receiptId,
        String ocrStatus,
        BigDecimal amount,
        String currencyCode,
        LocalDate receiptDate,
        String merchantName,
        /** The active expense category the receipt text points to (same matcher as a report's receipt scan). */
        UUID categoryId,
        String categoryName,
        /** How it was paid, as printed on the receipt (e.g. "UPI", "Cash", "Credit Card"). */
        String paymentMethod,
        BigDecimal taxAmount,
        String supplierGstin,
        String invoiceNumber,
        BigDecimal confidenceScore
) {
    /** Nothing read yet. */
    public static SupplementaryReceiptSuggestionResponse processing(UUID receiptId) {
        return new SupplementaryReceiptSuggestionResponse(receiptId, "PROCESSING", null, null, null, null, null, null, null, null, null, null, null);
    }
}
