package com.expense_management_service.service.impl;

import java.math.BigDecimal;

/**
 * Uniform return shape for every field extractor in this package: the extracted value (or
 * {@code null} if nothing was found) plus this extractor's own confidence in it (0-1 scale,
 * {@code null} if nothing was found). Letting every extractor return the same shape is what
 * makes {@link TextractResponseParserImpl} a thin orchestrator and each extractor trivially
 * unit-testable in isolation — a test asserts both the value and the confidence in one call.
 */
record ExtractionResult<T>(T value, BigDecimal confidence) {

    private static final ExtractionResult<?> EMPTY = new ExtractionResult<>(null, null);

    @SuppressWarnings("unchecked")
    static <T> ExtractionResult<T> empty() {
        return (ExtractionResult<T>) EMPTY;
    }

    static <T> ExtractionResult<T> of(T value, BigDecimal confidence) {
        return value == null ? empty() : new ExtractionResult<>(value, confidence);
    }

    boolean isPresent() {
        return value != null;
    }
}
