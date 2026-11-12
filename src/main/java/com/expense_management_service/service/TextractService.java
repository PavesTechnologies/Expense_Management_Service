package com.expense_management_service.service;

import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;

/**
 * Integrates with Amazon Textract to extract structured data from a document already
 * stored in S3. Exposes one method per Textract API used by the OCR document-type
 * strategies ({@code OcrDocumentStrategy} implementations) — each strategy picks whichever
 * is appropriate for the document type it handles, rather than every document going through
 * the same API.
 * <p>
 * Deliberately knows nothing about parsing, persistence, or OCR status — it takes an
 * object key and returns Textract's raw response. Callers own everything downstream.
 */
public interface TextractService {

    /**
     * Runs AWS Textract's {@code AnalyzeExpense} against the receipt stored at
     * {@code objectKey} and returns the raw response, unparsed. Appropriate for invoices
     * and receipts (its purpose-built use case).
     *
     * @param objectKey the S3 object key of the receipt, as stored via {@code StorageService}
     * @return the raw Textract {@code AnalyzeExpense} response
     * @throws TextractIntegrationException if the Textract call itself fails (auth, network,
     *                                       timeout, unsupported document, or a service-side error)
     */
    AnalyzeExpenseResponse analyzeExpense(String objectKey);

    /**
     * Runs AWS Textract's {@code AnalyzeDocument} (forms + tables) against the document stored
     * at {@code objectKey}. Used for structured documents that are not invoices/receipts —
     * e.g. bus/flight/train tickets and boarding passes — where field/value pairs are still
     * expected but {@code AnalyzeExpense}'s expense-specific field types do not apply.
     *
     * @param objectKey the S3 object key of the document, as stored via {@code StorageService}
     * @return the raw Textract {@code AnalyzeDocument} response
     * @throws TextractIntegrationException if the Textract call itself fails
     */
    AnalyzeDocumentResponse analyzeDocument(String objectKey);

    /**
     * Runs AWS Textract's {@code DetectDocumentText} (plain OCR, no structure) against the
     * document stored at {@code objectKey}. Used as the last-resort fallback for a document
     * whose type could not be determined, so at least raw text is recovered instead of nothing.
     *
     * @param objectKey the S3 object key of the document, as stored via {@code StorageService}
     * @return the raw Textract {@code DetectDocumentText} response
     * @throws TextractIntegrationException if the Textract call itself fails
     */
    DetectDocumentTextResponse detectDocumentText(String objectKey);
}
