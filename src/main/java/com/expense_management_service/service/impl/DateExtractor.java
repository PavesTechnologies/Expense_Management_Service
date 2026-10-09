package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.labelOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizeForLabelMatching;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 2: normalizes every documented receipt date format into a {@link LocalDate}, trying — in
 * order — Textract's standard date ExpenseTypes, then a label scan, then (Task 13) a raw-OCR-line
 * scan. Within the standard-type tier every field of a given type is tried (not just the first)
 * before moving to the next type, since a duplicate same-type field is sometimes unparseable OCR
 * noise while another one isn't. Format parsing itself lives in {@link ReceiptFieldParsingUtils},
 * shared with {@link TimeExtractor} and the travel-document parser.
 */
final class DateExtractor {

    /** Textract has no single canonical date field name across receipt templates — tried in this order. */
    private static final List<String> DATE_TYPE_PRIORITY = List.of(
            "INVOICE_RECEIPT_DATE", "RECEIPT_DATE", "DATE", "ORDER_DATE");

    /** When falling back to a label scan, a label naming one of these outranks a bare/generic "Date" label — same priority the user expects from the standard-type tier. */
    private static final List<String> DATE_LABEL_PRIORITY_KEYWORDS = List.of("INVOICE", "RECEIPT", "TRANSACTION");

    private static final Pattern DATE_LABEL_PATTERN = Pattern.compile("\\bDATE\\b", Pattern.CASE_INSENSITIVE);

    /** Strips a common leading label off a raw OCR line (e.g. "Date: 05/06/2026") before attempting to parse — {@code LocalDate.parse} requires the whole string to match, not just a substring. */
    private static final Pattern LEADING_DATE_LABEL_PREFIX = Pattern.compile(
            "(?i)^\\s*(invoice\\s+date|receipt\\s+date|transaction\\s+date|date)\\s*[:\\-]?\\s*");

    /** Fixed, conservative — a raw OCR line is a much weaker signal than a labeled/typed Textract field. */
    private static final BigDecimal OCR_FALLBACK_CONFIDENCE = new BigDecimal("0.30");

    ExtractionResult<LocalDate> extract(ExpenseFieldIndex index) {
        for (String type : DATE_TYPE_PRIORITY) {
            for (ExpenseField field : index.byType(type)) {
                LocalDate parsed = ReceiptFieldParsingUtils.parseDate(textOf(field));
                if (parsed != null) {
                    return ExtractionResult.of(parsed, normalizedConfidenceOf(field));
                }
            }
        }

        ExtractionResult<LocalDate> labelResult = extractFromLabelScan(index.allFields());
        if (labelResult.isPresent()) {
            return labelResult;
        }

        return extractFromRawBlocks(index.blocks());
    }

    private ExtractionResult<LocalDate> extractFromLabelScan(List<ExpenseField> allFields) {
        List<ExpenseField> dateLabeledFields = allFields.stream()
                .filter(field -> {
                    String label = labelOf(field);
                    return label != null && DATE_LABEL_PATTERN.matcher(label).find();
                })
                .sorted(Comparator.comparingInt(this::labelPriorityRank))
                .toList();

        for (ExpenseField field : dateLabeledFields) {
            LocalDate parsed = ReceiptFieldParsingUtils.parseDate(textOf(field));
            if (parsed != null) {
                return ExtractionResult.of(parsed, normalizedConfidenceOf(field));
            }
        }
        return ExtractionResult.empty();
    }

    private int labelPriorityRank(ExpenseField field) {
        String normalizedLabel = normalizeForLabelMatching(labelOf(field));
        for (int i = 0; i < DATE_LABEL_PRIORITY_KEYWORDS.size(); i++) {
            if (normalizedLabel.contains(DATE_LABEL_PRIORITY_KEYWORDS.get(i))) {
                return i;
            }
        }
        return DATE_LABEL_PRIORITY_KEYWORDS.size();
    }

    /** Task 13: scans raw OCR lines for a date-parseable substring once no structured or labeled field yielded one. */
    private ExtractionResult<LocalDate> extractFromRawBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.blockType() != BlockType.LINE || block.text() == null) {
                continue;
            }
            String candidateText = LEADING_DATE_LABEL_PREFIX.matcher(block.text().trim()).replaceFirst("").trim();
            LocalDate parsed = ReceiptFieldParsingUtils.parseDate(candidateText);
            if (parsed != null) {
                return ExtractionResult.of(parsed, OCR_FALLBACK_CONFIDENCE);
            }
        }
        return ExtractionResult.empty();
    }
}
