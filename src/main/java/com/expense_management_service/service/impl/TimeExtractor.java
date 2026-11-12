package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.regex.Pattern;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.labelOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 3: normalizes every documented receipt time format ("13:52", "01:52 PM", "13:52:08") into
 * a {@link LocalTime}. Textract has no single reliable structured time field across templates, so
 * this tries, in order: a time embedded within one of the date-type fields' own text (the common
 * "05/06/2026 13:52:08" shape), a dedicated TIME/RECEIPT_TIME field, a label scan, and finally
 * (Task 3/13) a raw-OCR-line scan. Returns {@code null} when genuinely absent — never a guess.
 * <p>
 * Deliberately independent of {@link DateExtractor} (does its own date-type-field scan rather
 * than being handed DateExtractor's winning field) so each extractor stays separately testable
 * per Task 14, at the cost of a small, intentional duplication of the date-type priority list.
 */
final class TimeExtractor {

    private static final List<String> DATE_TYPE_PRIORITY = List.of(
            "INVOICE_RECEIPT_DATE", "RECEIPT_DATE", "DATE", "ORDER_DATE");
    private static final List<String> TIME_TYPE_PRIORITY = List.of("RECEIPT_TIME", "TIME");
    private static final Pattern TIME_LABEL_PATTERN = Pattern.compile("\\bTIME\\b", Pattern.CASE_INSENSITIVE);

    /** Fixed, conservative — a raw OCR line is a much weaker signal than a labeled/typed Textract field. */
    private static final BigDecimal OCR_FALLBACK_CONFIDENCE = new BigDecimal("0.30");

    ExtractionResult<LocalTime> extract(ExpenseFieldIndex index) {
        for (String type : DATE_TYPE_PRIORITY) {
            for (ExpenseField field : index.byType(type)) {
                String embedded = ReceiptFieldParsingUtils.extractEmbeddedTime(textOf(field));
                LocalTime parsed = ReceiptFieldParsingUtils.parseTime(embedded);
                if (parsed != null) {
                    return ExtractionResult.of(parsed, normalizedConfidenceOf(field));
                }
            }
        }

        for (String type : TIME_TYPE_PRIORITY) {
            for (ExpenseField field : index.byType(type)) {
                LocalTime parsed = ReceiptFieldParsingUtils.parseTime(textOf(field));
                if (parsed != null) {
                    return ExtractionResult.of(parsed, normalizedConfidenceOf(field));
                }
            }
        }

        for (ExpenseField field : index.allFields()) {
            String label = labelOf(field);
            if (label != null && TIME_LABEL_PATTERN.matcher(label).find()) {
                LocalTime parsed = ReceiptFieldParsingUtils.parseTime(textOf(field));
                if (parsed != null) {
                    return ExtractionResult.of(parsed, normalizedConfidenceOf(field));
                }
            }
        }

        return extractFromRawBlocks(index.blocks());
    }

    /** Task 13: scans raw OCR lines for an embedded time once no structured or labeled field yielded one. */
    private ExtractionResult<LocalTime> extractFromRawBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.blockType() != BlockType.LINE || block.text() == null) {
                continue;
            }
            LocalTime parsed = ReceiptFieldParsingUtils.parseTime(ReceiptFieldParsingUtils.extractEmbeddedTime(block.text()));
            if (parsed != null) {
                return ExtractionResult.of(parsed, OCR_FALLBACK_CONFIDENCE);
            }
        }
        return ExtractionResult.empty();
    }
}
