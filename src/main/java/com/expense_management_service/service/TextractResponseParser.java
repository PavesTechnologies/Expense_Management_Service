package com.expense_management_service.service;

import com.expense_management_service.dto.ocr.ParsedReceiptData;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;

/**
 * Parses a raw Textract {@code AnalyzeExpense} response into a structured
 * {@link ParsedReceiptData}. Pure mapping logic only — no I/O, no AWS SDK calls beyond
 * reading the response object passed in, no persistence.
 */
public interface TextractResponseParser {

    /**
     * @param rawResponse the raw response from {@code TextractService.analyzeExpense}
     * @return extracted fields; individual fields are {@code null} where Textract found or
     * could parse nothing — this method never throws for a partially-empty extraction
     */
    ParsedReceiptData parse(AnalyzeExpenseResponse rawResponse);
}
