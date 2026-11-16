package com.expense_management_service.dto.request;

/**
 * Finance/AP review decision for a late receipt. {@code note} is required when rejecting, and when verifying a receipt
 * whose OCR total differs from the claimed amount.
 */
public record LateReceiptReviewRequest(String note) {
}
