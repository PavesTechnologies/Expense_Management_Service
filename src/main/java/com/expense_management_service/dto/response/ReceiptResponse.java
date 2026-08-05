package com.expense_management_service.dto.response;

import com.expense_management_service.enums.OcrStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Receipt metadata only — never includes the S3 object key or any AWS-specific detail.
 * Use {@code GET /receipts/{receiptId}/view} or {@code /download} to obtain a pre-signed URL.
 * {@code lineItemId} is {@code null} until the receipt is confirmed/linked (EP03-S4) — a receipt
 * belongs to its report from the moment it's uploaded, independent of any line item.
 */
public record ReceiptResponse(
        UUID receiptId,
        UUID reportId,
        UUID lineItemId,
        String originalFileName,
        String contentType,
        Integer fileSize,
        String uploadedBy,
        LocalDateTime uploadedAt,
        OcrStatus ocrStatus
) {
}
