package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.OcrDocumentStrategy;
import com.expense_management_service.service.TextractResponseParser;
import com.expense_management_service.service.TextractService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;

/**
 * First strategy tried for every document (see {@code @Order}): {@code AnalyzeExpense} is
 * Textract's purpose-built invoice/receipt API and correctly handles the large majority of what
 * this system processes. Reports {@link OcrExtractionResult#meaningful()} only when it actually
 * found expense-shaped data — a document with no vendor name, no invoice number, and no total
 * (e.g. a travel ticket) falls through to {@link AnalyzeDocumentOcrStrategy} instead of being
 * accepted as a mostly-empty "successful" extraction.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class AnalyzeExpenseOcrStrategy implements OcrDocumentStrategy {

    public static final String OCR_VERSION = "AnalyzeExpense";

    private final TextractService textractService;
    private final TextractResponseParser textractResponseParser;

    @Override
    public String ocrVersion() {
        return OCR_VERSION;
    }

    @Override
    public OcrExtractionResult extract(String objectKey) {
        AnalyzeExpenseResponse rawResponse = textractService.analyzeExpense(objectKey);
        ParsedReceiptData data = textractResponseParser.parse(rawResponse);
        boolean meaningful = data.merchantName() != null || data.invoiceNumber() != null || data.totalAmount() != null;
        return new OcrExtractionResult(data, meaningful, OCR_VERSION);
    }
}
