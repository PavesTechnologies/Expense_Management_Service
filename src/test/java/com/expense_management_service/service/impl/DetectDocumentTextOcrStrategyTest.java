package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.service.TextractService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Last-resort strategy for a document whose type could not be determined (Issue 9's "Unknown"
 * branch) — verifies it always reports {@code meaningful() == true} (nothing left to fall back
 * to) and recovers at least a currency and confidence figure from plain OCR text.
 */
@ExtendWith(MockitoExtension.class)
class DetectDocumentTextOcrStrategyTest {

    @Mock
    private TextractService textractService;
    @InjectMocks
    private DetectDocumentTextOcrStrategy strategy;

    @Test
    void ocrVersion_isDetectDocumentText() {
        assertThat(strategy.ocrVersion()).isEqualTo("DetectDocumentText");
    }

    @Test
    void extract_isAlwaysMeaningful_andDetectsCurrencyFromRawText() {
        DetectDocumentTextResponse raw = DetectDocumentTextResponse.builder()
                .blocks(
                        Block.builder().blockType(BlockType.LINE).text("Some Random Ticket").confidence(70.0f).build(),
                        Block.builder().blockType(BlockType.LINE).text("Total: INR 450.00").confidence(80.0f).build())
                .build();
        when(textractService.detectDocumentText("key")).thenReturn(raw);

        OcrExtractionResult result = strategy.extract("key");

        assertThat(result.meaningful()).isTrue();
        assertThat(result.data().currencyCode()).isEqualTo("INR");
        assertThat(result.data().confidenceScore()).isEqualByComparingTo("0.7500");
        assertThat(result.data().merchantName()).isNull();
    }

    @Test
    void extract_leavesFieldsNull_whenNoLinesDetected() {
        DetectDocumentTextResponse raw = DetectDocumentTextResponse.builder().blocks(List.of()).build();
        when(textractService.detectDocumentText("key")).thenReturn(raw);

        OcrExtractionResult result = strategy.extract("key");

        assertThat(result.meaningful()).isTrue();
        assertThat(result.data().currencyCode()).isNull();
        assertThat(result.data().confidenceScore()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    }
}
