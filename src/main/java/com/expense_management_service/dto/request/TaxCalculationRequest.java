package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Preview request: {@code taxCodeId} overrides the category's mapping; {@code enteredTax} is compared with the calculation. */
public record TaxCalculationRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        @NotNull LocalDate expenseDate,
        UUID categoryId,
        UUID taxCodeId,
        @PositiveOrZero BigDecimal enteredTax,
        /** Tax OCR read off the receipt, to preview the validation status; optional. */
        @PositiveOrZero BigDecimal ocrTaxAmount,
        /** OCR's confidence in it (0-1); optional. */
        BigDecimal ocrTaxConfidence
) {
}
