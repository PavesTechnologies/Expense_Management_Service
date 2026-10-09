package com.expense_management_service.dto.ocr;

import java.math.BigDecimal;

/**
 * Per-field confidence breakdown (0-1 scale, same scale as {@link ParsedReceiptData#confidenceScore()}),
 * for UI highlighting of individual low-confidence fields rather than one opaque overall number.
 * {@code null} on any component means that field was never found at all (nothing to score), as
 * opposed to {@link BigDecimal#ZERO} which means it was found but Textract itself reported zero
 * confidence in it.
 *
 * @param merchantConfidence confidence in the selected merchant name
 * @param dateConfidence     confidence in the selected receipt date
 * @param amountConfidence   confidence in the selected total amount
 * @param taxConfidence      confidence in the selected tax amount
 * @param currencyConfidence confidence in the detected currency code
 */
public record FieldConfidence(
        BigDecimal merchantConfidence,
        BigDecimal dateConfidence,
        BigDecimal amountConfidence,
        BigDecimal taxConfidence,
        BigDecimal currencyConfidence
) {
    private static final FieldConfidence EMPTY = new FieldConfidence(null, null, null, null, null);

    /** Used when a {@link ParsedReceiptData} is built via its legacy 10-arg constructor (tests, other parsers) — no breakdown available. */
    public static FieldConfidence empty() {
        return EMPTY;
    }
}
