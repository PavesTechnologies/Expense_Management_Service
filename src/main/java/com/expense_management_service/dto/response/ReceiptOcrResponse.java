package com.expense_management_service.dto.response;

import com.expense_management_service.enums.OcrStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * OCR extraction result for a single attempt, exposed for side-by-side review against the
 * original {@code Receipt} on the reused Manual Entry screen.
 * <p>
 * Deliberately excludes line-item-level detail (products, SKUs, quantities, unit prices) — one
 * receipt maps to one expense line item, so only receipt-level totals are returned.
 *
 * @param subtotal          pre-tax subtotal, or {@code null} if the receipt had no separate subtotal line
 * @param taxAmount         total tax — the sum of CGST/SGST/IGST when Textract reported them
 *                          separately, never just one component
 * @param totalAmount       the receipt's grand total (named {@code amount} internally on the entity)
 * @param paymentMethod     e.g. "UPI", "Cash", "Credit Card" — {@code null} if not found on the receipt
 * @param possibleDuplicate computed at read time — true if an existing completed OCR result for
 *                          the same employee shares merchant, amount, currency, and date.
 *                          Advisory only; never blocks anything, the employee decides.
 * @param reviewRecommended computed at read time — true if the attempt failed, a core field
 *                          (date/merchant/amount) is missing, confidence is below the configured
 *                          threshold, or subtotal + tax doesn't reconcile with the total.
 */
public record ReceiptOcrResponse(
        UUID ocrId,
        UUID receiptId,
        String merchantName,
        String invoiceNumber,
        LocalDate receiptDate,
        LocalTime receiptTime,
        String currencyCode,
        BigDecimal subtotal,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        String paymentMethod,
        BigDecimal confidenceScore,
        OcrStatus processingStatus,
        String failureReason,
        LocalDateTime processedAt,
        Long processingDurationMs,
        String ocrEngine,
        String ocrVersion,
        boolean possibleDuplicate,
        boolean reviewRecommended
) {
}
