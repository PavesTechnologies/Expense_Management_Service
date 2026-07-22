package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiptOcrResponse(
        UUID ocrId,
        UUID receiptId,
        String merchantName,
        LocalDate receiptDate,
        BigDecimal amount,
        String currencyCode,
        BigDecimal confidenceScore,
        LocalDateTime processedAt
) {
}
