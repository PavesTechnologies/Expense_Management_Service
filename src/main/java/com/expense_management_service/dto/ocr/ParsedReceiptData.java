package com.expense_management_service.dto.ocr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Structured result of parsing a raw Textract {@code AnalyzeExpense} response — the output of
 * {@code TextractResponseParser}, and the input {@code OCRService} persists as a
 * {@code ReceiptOcr} row. Not a REST request/response — an internal transfer object between the
 * two, so it lives alongside future OCR-internal DTOs under {@code dto.ocr} rather than
 * {@code dto.request}/{@code dto.response}.
 * <p>
 * Deliberately excludes line-item-level detail (products, SKUs, quantities, unit prices) —
 * one receipt maps to one expense line item in this system, so only receipt-level totals matter.
 *
 * @param merchantName    extracted vendor name, or {@code null} if Textract found none
 * @param invoiceNumber   extracted invoice/receipt id, or {@code null} — not every receipt template has one
 * @param receiptDate     extracted receipt/invoice date, or {@code null} if absent or unparseable
 * @param receiptTime     extracted receipt time, or {@code null} if absent, unparseable, or not present on the receipt
 * @param currencyCode    extracted ISO currency code, or {@code null} if Textract didn't report one
 * @param subtotal        extracted pre-tax subtotal, or {@code null} if the receipt has no separate subtotal line
 * @param taxAmount       total tax — the sum of CGST/SGST/IGST when Textract reports them as separate
 *                        line items (Indian GST receipts), or the single TAX field otherwise; {@code null} if
 *                        no tax line was found at all
 * @param totalAmount     extracted grand total, or {@code null} if absent or unparseable
 * @param paymentMethod   extracted payment method (e.g. "UPI", "Cash", "Credit Card"), or {@code null} if not found —
 *                        Textract has no dedicated field for this, so it's matched by field label
 * @param confidenceScore aggregate confidence (0–1) across whichever key fields were found
 * @param fieldConfidence per-field confidence breakdown, for UI highlighting of specific low-confidence
 *                        fields rather than just the one aggregate number
 */
public record ParsedReceiptData(
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
        FieldConfidence fieldConfidence
) {
    /**
     * Legacy 10-arg shape, kept so every existing caller (tests, {@code TravelDocumentResponseParser},
     * {@code DetectDocumentTextOcrStrategy}) keeps compiling unchanged — only
     * {@code TextractResponseParserImpl} needs the per-field breakdown, so only it uses the full
     * canonical constructor above.
     */
    public ParsedReceiptData(
            String merchantName,
            String invoiceNumber,
            LocalDate receiptDate,
            LocalTime receiptTime,
            String currencyCode,
            BigDecimal subtotal,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String paymentMethod,
            BigDecimal confidenceScore
    ) {
        this(merchantName, invoiceNumber, receiptDate, receiptTime, currencyCode,
                subtotal, taxAmount, totalAmount, paymentMethod, confidenceScore, FieldConfidence.empty());
    }
}
