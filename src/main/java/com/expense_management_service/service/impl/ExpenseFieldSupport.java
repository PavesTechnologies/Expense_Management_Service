package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Field-level helpers shared by every extractor in this package — reading a field's value/label
 * text, punctuation-insensitive label matching, and confidence normalization. Centralized here so
 * each extractor's own code is pure selection/parsing strategy, not repeated field-reading
 * boilerplate.
 */
final class ExpenseFieldSupport {

    private ExpenseFieldSupport() {
    }

    static String textOf(ExpenseField field) {
        if (field == null || field.valueDetection() == null || field.valueDetection().text() == null) {
            return null;
        }
        String text = field.valueDetection().text();
        return text.isBlank() ? null : text;
    }

    static String labelOf(ExpenseField field) {
        if (field == null || field.labelDetection() == null || field.labelDetection().text() == null) {
            return null;
        }
        String label = field.labelDetection().text();
        return label.isBlank() ? null : label;
    }

    /** Textract's raw 0-100 confidence for this field's detected value, if present. */
    static Optional<Float> rawConfidenceOf(ExpenseField field) {
        if (field == null || field.valueDetection() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(field.valueDetection().confidence());
    }

    /** Textract's confidence normalized to the 0-1 scale {@code ParsedReceiptData}/{@code ReceiptOcr} store, or {@code null} if absent. */
    static BigDecimal normalizedConfidenceOf(ExpenseField field) {
        return rawConfidenceOf(field)
                .map(confidence -> BigDecimal.valueOf(confidence / 100.0).setScale(4, RoundingMode.HALF_UP))
                .orElse(null);
    }

    /** Uppercases and strips everything but letters/digits, so "UN-PAID" and "un paid" both match the keyword "UNPAID". */
    static String normalizeForLabelMatching(String text) {
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** Every field (in encounter order) whose label contains {@code keyword} — punctuation-insensitive, substring match. */
    static List<ExpenseField> findAllFieldsByLabelContaining(List<ExpenseField> fields, String keyword) {
        String normalizedKeyword = normalizeForLabelMatching(keyword);
        return fields.stream()
                .filter(field -> {
                    String label = labelOf(field);
                    return label != null && normalizeForLabelMatching(label).contains(normalizedKeyword);
                })
                .toList();
    }

    /** First field whose label contains {@code keyword} — convenience for extractors that only need one. */
    static Optional<ExpenseField> findFieldByLabelContaining(List<ExpenseField> fields, String keyword) {
        List<ExpenseField> matches = findAllFieldsByLabelContaining(fields, keyword);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }
}
