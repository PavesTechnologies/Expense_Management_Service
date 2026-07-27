package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Receipt metadata only — never includes the S3 object key or any AWS-specific detail.
 * Use {@code GET /receipts/{receiptId}/view} or {@code /download} to obtain a pre-signed URL.
 */
public record ReceiptResponse(
        UUID receiptId,
        UUID lineItemId,
        String originalFileName,
        String contentType,
        Integer fileSize,
        String uploadedBy,
        LocalDateTime uploadedAt
) {
}
