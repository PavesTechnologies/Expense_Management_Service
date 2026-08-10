package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.OcrDocumentStrategy;
import com.expense_management_service.service.TextractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Last-resort strategy (see {@code @Order}) for a document whose type could not be determined —
 * neither {@link AnalyzeExpenseOcrStrategy} nor {@link AnalyzeDocumentOcrStrategy} found anything
 * structured. Runs Textract's {@code DetectDocumentText} (plain OCR, no structure) so at least
 * the currency and an overall confidence figure are recovered instead of nothing; every other
 * field is left {@code null} rather than guessed from unlabeled text, so the employee's manual
 * review (triggered automatically — a result this sparse always fails the missing-field checks in
 * {@code OCRServiceImpl.isReviewRecommended}) starts from an honest baseline, not a fabricated one.
 * <p>
 * Always reports {@link OcrExtractionResult#meaningful()} {@code true} — being the last strategy
 * in the chain, there is nothing left to fall back to, so whatever it finds (even nothing) is
 * the final result.
 */
@Component
@Order(3)
@RequiredArgsConstructor
@Slf4j
public class DetectDocumentTextOcrStrategy implements OcrDocumentStrategy {

    public static final String OCR_VERSION = "DetectDocumentText";

    private final TextractService textractService;

    @Override
    public String ocrVersion() {
        return OCR_VERSION;
    }

    @Override
    public OcrExtractionResult extract(String objectKey) {
        DetectDocumentTextResponse rawResponse = textractService.detectDocumentText(objectKey);
        List<Block> lineBlocks = rawResponse.blocks() == null ? List.of() : rawResponse.blocks().stream()
                .filter(block -> block.blockType() == BlockType.LINE)
                .toList();

        String fullText = lineBlocks.stream().map(Block::text).filter(Objects::nonNull).collect(Collectors.joining("\n"));
        String currencyCode = ReceiptFieldParsingUtils.detectCurrencyFromText(fullText);
        BigDecimal confidenceScore = averageConfidence(lineBlocks);

        log.debug("[OCR] DetectDocumentText fallback extracted {} lines, currency={}, confidence={}",
                lineBlocks.size(), currencyCode, confidenceScore);

        ParsedReceiptData data = new ParsedReceiptData(
                null, null, null, null, currencyCode, null, null, null, null, confidenceScore);
        return new OcrExtractionResult(data, true, OCR_VERSION);
    }

    private BigDecimal averageConfidence(List<Block> lineBlocks) {
        List<Float> confidences = lineBlocks.stream().map(Block::confidence).filter(Objects::nonNull).toList();
        if (confidences.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double average = confidences.stream().mapToDouble(Float::doubleValue).average().orElse(0.0);
        return BigDecimal.valueOf(average / 100.0).setScale(4, RoundingMode.HALF_UP);
    }
}
