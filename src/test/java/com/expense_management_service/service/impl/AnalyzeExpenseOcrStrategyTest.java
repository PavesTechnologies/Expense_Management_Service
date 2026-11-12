package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.TextractResponseParser;
import com.expense_management_service.service.TextractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeExpenseOcrStrategyTest {

    @Mock
    private TextractService textractService;
    @Mock
    private TextractResponseParser textractResponseParser;
    @InjectMocks
    private AnalyzeExpenseOcrStrategy strategy;

    @Test
    void ocrVersion_isAnalyzeExpense() {
        assertThat(strategy.ocrVersion()).isEqualTo("AnalyzeExpense");
    }

    @Test
    void extract_reportsMeaningful_whenMerchantNameFound() {
        AnalyzeExpenseResponse raw = AnalyzeExpenseResponse.builder().build();
        when(textractService.analyzeExpense("key")).thenReturn(raw);
        when(textractResponseParser.parse(raw)).thenReturn(new ParsedReceiptData(
                "Pizza Hut", null, null, null, null, null, null, null, null, BigDecimal.ZERO));

        OcrExtractionResult result = strategy.extract("key");

        assertThat(result.meaningful()).isTrue();
        assertThat(result.ocrVersion()).isEqualTo("AnalyzeExpense");
        assertThat(result.data().merchantName()).isEqualTo("Pizza Hut");
    }

    @Test
    void extract_reportsMeaningful_whenOnlyTotalAmountFound() {
        AnalyzeExpenseResponse raw = AnalyzeExpenseResponse.builder().build();
        when(textractService.analyzeExpense("key")).thenReturn(raw);
        when(textractResponseParser.parse(raw)).thenReturn(new ParsedReceiptData(
                null, null, null, null, null, null, null, new BigDecimal("100.00"), null, BigDecimal.ZERO));

        assertThat(strategy.extract("key").meaningful()).isTrue();
    }

    @Test
    void extract_reportsNotMeaningful_whenNothingExpenseShapedFound() {
        AnalyzeExpenseResponse raw = AnalyzeExpenseResponse.builder().build();
        when(textractService.analyzeExpense("key")).thenReturn(raw);
        when(textractResponseParser.parse(raw)).thenReturn(new ParsedReceiptData(
                null, null, LocalDate.now(), null, "INR", null, null, null, null, BigDecimal.ZERO));

        assertThat(strategy.extract("key").meaningful()).isFalse();
    }
}
