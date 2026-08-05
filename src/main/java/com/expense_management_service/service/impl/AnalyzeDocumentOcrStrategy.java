package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.OcrDocumentStrategy;
import com.expense_management_service.service.TextractService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;

/**
 * Second strategy tried (see {@code @Order}) — only reached when
 * {@link AnalyzeExpenseOcrStrategy} found nothing expense-shaped. Runs Textract's
 * {@code AnalyzeDocument} (forms + tables) and maps its key/value pairs via
 * {@link TravelDocumentResponseParser} — this is what makes bus/flight/train tickets and
 * boarding passes work: they have labeled fields (PNR, fare, departure date/time, operator)
 * but no expense-specific structure {@code AnalyzeExpense} recognizes.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class AnalyzeDocumentOcrStrategy implements OcrDocumentStrategy {

    public static final String OCR_VERSION = "AnalyzeDocument";

    private final TextractService textractService;
    private final TravelDocumentResponseParser travelDocumentResponseParser;

    @Override
    public String ocrVersion() {
        return OCR_VERSION;
    }

    @Override
    public OcrExtractionResult extract(String objectKey) {
        AnalyzeDocumentResponse rawResponse = textractService.analyzeDocument(objectKey);
        ParsedReceiptData data = travelDocumentResponseParser.parse(rawResponse);
        boolean meaningful = data.merchantName() != null || data.invoiceNumber() != null
                || data.totalAmount() != null || data.receiptDate() != null;
        return new OcrExtractionResult(data, meaningful, OCR_VERSION);
    }
}
