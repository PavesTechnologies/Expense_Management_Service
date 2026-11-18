package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReceiptReleaseResponse(
        UUID releaseId,
        UUID receiptId,
        String status,
        String requestedBy,
        String requestReason,
        LocalDateTime requestedAt,
        String decidedBy,
        String decisionReason,
        LocalDateTime decidedAt,
        LocalDateTime consumedAt
) {
}
