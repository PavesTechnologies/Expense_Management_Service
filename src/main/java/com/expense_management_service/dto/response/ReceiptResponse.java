package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiptResponse(
        UUID receiptId,
        UUID lineItemId,
        String fileName,
        String filePath,
        String fileType,
        Integer fileSize,
        String uploadedBy,
        LocalDateTime uploadedAt,
        String ocrStatus,
        String fileHash
) {
}
