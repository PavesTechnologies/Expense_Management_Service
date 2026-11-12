package com.expense_management_service.dto.ocr;

/**
 * Result of one {@code OcrDocumentStrategy}'s attempt to extract data from a document.
 *
 * @param data       the extracted fields (possibly mostly {@code null} if extraction found little)
 * @param meaningful whether this strategy found enough to be worth keeping — {@code false} tells
 *                   the strategy chain to try the next strategy instead of accepting this result
 * @param ocrVersion identifies which Textract API produced this result (e.g. "AnalyzeExpense",
 *                   "AnalyzeDocument", "DetectDocumentText"); persisted on {@code ReceiptOcr.ocrVersion}
 */
public record OcrExtractionResult(ParsedReceiptData data, boolean meaningful, String ocrVersion) {
}
