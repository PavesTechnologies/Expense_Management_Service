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
        String merchantName
) {
}
