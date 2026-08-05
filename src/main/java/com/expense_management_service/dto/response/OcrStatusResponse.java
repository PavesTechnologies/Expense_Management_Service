package com.expense_management_service.dto.response;

import com.expense_management_service.enums.OcrStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lightweight receipt-level OCR status, meant for polling from the "Loading" step of the
 * upload workflow without pulling the full extracted result.
 */
public record OcrStatusResponse(
        UUID receiptId,
        OcrStatus ocrStatus,
        LocalDateTime lastUpdated
) {
}
