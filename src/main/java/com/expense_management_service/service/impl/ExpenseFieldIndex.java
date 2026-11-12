package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexes one Textract {@code AnalyzeExpense} {@link ExpenseDocument} for the extractors in this
 * package. Built once per {@code parse()} call and handed to every extractor, so each extractor's
 * own code stays free of field-indexing boilerplate and can be unit-tested by constructing this
 * directly from hand-built fields — no {@code AnalyzeExpenseResponse} required.
 * <p>
 * Deliberately indexes by type as {@code Map<String, List<ExpenseField>>} rather than a single
 * field per type: Textract commonly returns more than one field of the same {@code ExpenseType}
 * on one receipt (e.g. two {@code VENDOR_NAME} fields — a brand/logo line and a separate legal
 * registration line). A single-value index silently discards every candidate after the first,
 * which was the root cause of merchant/date fields being dropped even though Textract had
 * actually detected them.
 */
final class ExpenseFieldIndex {

    private final List<ExpenseField> allFields;
    private final Map<String, List<ExpenseField>> fieldsByType;
    private final List<Block> blocks;

    private ExpenseFieldIndex(List<ExpenseField> allFields, Map<String, List<ExpenseField>> fieldsByType, List<Block> blocks) {
        this.allFields = allFields;
        this.fieldsByType = fieldsByType;
        this.blocks = blocks;
    }

    static ExpenseFieldIndex from(ExpenseDocument document) {
        List<ExpenseField> allFields = document.summaryFields() != null ? document.summaryFields() : List.of();
        Map<String, List<ExpenseField>> byType = allFields.stream()
                .filter(field -> field.type() != null && field.type().text() != null)
                .collect(Collectors.groupingBy(
                        field -> field.type().text().toUpperCase(Locale.ROOT),
                        LinkedHashMap::new,
                        Collectors.toList()));
        // Task 13 fallback source: the raw OCR block graph (WORD/LINE/PAGE) Textract includes
        // alongside the expense-specific summary fields — used only when the structured fields
        // above have nothing for a given concept.
        List<Block> blocks = document.hasBlocks() ? document.blocks() : List.of();
        return new ExpenseFieldIndex(allFields, byType, blocks);
    }

    List<ExpenseField> allFields() {
        return allFields;
    }

    /** Every field Textract tagged with this ExpenseType — empty list, never null, if none. */
    List<ExpenseField> byType(String type) {
        return fieldsByType.getOrDefault(type, List.of());
    }

    /** First field of this type, or {@code null} — convenience for extractors that only ever want one. */
    ExpenseField firstByType(String type) {
        List<ExpenseField> candidates = byType(type);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    List<Block> blocks() {
        return blocks;
    }
}
