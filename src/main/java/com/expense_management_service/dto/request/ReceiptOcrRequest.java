package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReceiptOcrRequest(
        @NotNull UUID receiptId,
        @Size(max = 255) String merchantName,
        LocalDate receiptDate,
        BigDecimal amount,
        @Size(max = 255) String currencyCode,
        BigDecimal confidenceScore
) {
}
