package com.expense_management_service.service;

import com.expense_management_service.dto.ocr.OcrExtractionResult;

/**
 * One way of extracting receipt-shaped data from a document stored in S3 — the Strategy Pattern
 * seam for Issue 9 (travel documents). {@code OCRServiceImpl} holds an ordered list of these
 * (ordered via {@code @Order}) and tries each in turn until one reports a
 * {@link OcrExtractionResult#meaningful()} result, rather than deciding the document's type
 * upfront: Textract's own response — did {@code AnalyzeExpense} find expense-shaped fields? did
 * {@code AnalyzeDocument} find form fields? — is a more reliable signal than any heuristic this
 * codebase could apply to the raw file before ever calling Textract.
 * <p>
 * Adding a new document type (e.g. a dedicated hotel-folio strategy) means adding a new class
 * that implements this interface and giving it an {@code @Order} — no existing strategy or the
 * orchestration in {@code OCRServiceImpl} needs to change.
 * <p>
 * A strategy should throw {@link TextractNotApplicableException} when the underlying Textract
 * operation itself reports "this document doesn't fit this operation" — the chain treats that as
 * "try the next strategy". Any other {@link TextractIntegrationException} (throttling, a
 * corrupted file, a network error) is a genuine failure and is left to propagate, ending the
 * chain rather than being silently retried against a different API.
 */
public interface OcrDocumentStrategy {

    /** Identifies this strategy for logging and for {@code ReceiptOcr.ocrVersion} — e.g. "AnalyzeExpense". */
    String ocrVersion();

    /**
     * Attempts extraction against the document at {@code objectKey}.
     *
     * @throws TextractNotApplicableException if the underlying Textract operation reports this
     *                                         document's shape doesn't fit — signals the chain to try the next strategy
     * @throws TextractIntegrationException   for any other Textract failure — stops the chain
     */
    OcrExtractionResult extract(String objectKey);
}
