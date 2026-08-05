package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.TextractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeDocumentOcrStrategyTest {

    @Mock
    private TextractService textractService;
    @Mock
    private TravelDocumentResponseParser travelDocumentResponseParser;
    @InjectMocks
    private AnalyzeDocumentOcrStrategy strategy;

    @Test
    void ocrVersion_isAnalyzeDocument() {
        assertThat(strategy.ocrVersion()).isEqualTo("AnalyzeDocument");
    }

    @Test
    void extract_reportsMeaningful_whenTravelDataFound() {
        AnalyzeDocumentResponse raw = AnalyzeDocumentResponse.builder().build();
        when(textractService.analyzeDocument("key")).thenReturn(raw);
        when(travelDocumentResponseParser.parse(raw)).thenReturn(new ParsedReceiptData(
                "IndiGo", "PNR123", null, null, "INR", null, null, new BigDecimal("2500.00"), null, BigDecimal.ZERO));

        OcrExtractionResult result = strategy.extract("key");

        assertThat(result.meaningful()).isTrue();
        assertThat(result.ocrVersion()).isEqualTo("AnalyzeDocument");
    }

    @Test
    void extract_reportsNotMeaningful_whenNoFormFieldsFound() {
        AnalyzeDocumentResponse raw = AnalyzeDocumentResponse.builder().build();
        when(textractService.analyzeDocument("key")).thenReturn(raw);
        when(travelDocumentResponseParser.parse(raw)).thenReturn(new ParsedReceiptData(
                null, null, null, null, null, null, null, null, null, BigDecimal.ZERO));

        assertThat(strategy.extract("key").meaningful()).isFalse();
    }
}
