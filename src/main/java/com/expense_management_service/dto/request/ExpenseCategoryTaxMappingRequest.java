package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** {@code effectiveTo} is inclusive; null = open-ended. */
public record ExpenseCategoryTaxMappingRequest(
        @NotNull UUID taxCodeId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
