package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/** A receipt attached to a supplementary claim item. */
public record SupplementaryReceiptResponse(
        UUID receiptId,
        String originalFileName,
        String attachmentKind,
        Integer fileSize,
        LocalDateTime uploadedAt
) {
}
