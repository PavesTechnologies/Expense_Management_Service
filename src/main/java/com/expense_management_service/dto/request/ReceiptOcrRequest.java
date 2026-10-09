package com.expense_management_service.dto.request;

import com.expense_management_service.enums.OcrStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record ReceiptOcrRequest(
        @NotNull UUID receiptId,
        @Size(max = 255) String merchantName,
        @Size(max = 255) String invoiceNumber,
        LocalDate receiptDate,
        LocalTime receiptTime,
        @Size(max = 255) String currencyCode,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        @Size(max = 50) String paymentMethod,
        BigDecimal confidenceScore,
        OcrStatus processingStatus,
        @Size(max = 500) String failureReason
) {
}
