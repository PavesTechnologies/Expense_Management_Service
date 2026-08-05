package com.expense_management_service.dto.response;

import com.expense_management_service.enums.OcrStatus;

import java.util.UUID;

/**
 * Deliberately narrow — the upload endpoint's job is to acknowledge the receipt was saved and
 * that OCR has been queued, nothing more. Full metadata is available via {@code GET /receipts/{id}};
 * live OCR progress via {@code GET /receipts/{id}/ocr/status}, which the frontend is expected to
 * poll rather than wait on this response for anything beyond the initial snapshot.
 */
public record ReceiptUploadResponse(
        UUID receiptId,
        OcrStatus processingStatus
) {
}
