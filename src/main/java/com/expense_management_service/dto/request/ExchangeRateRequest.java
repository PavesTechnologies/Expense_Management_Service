package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExchangeRateRequest(
        @NotNull UUID fromCurrencyId,
        @NotNull UUID toCurrencyId,
        @NotNull @Positive BigDecimal rate,
        @NotNull LocalDate effectiveDate,
        @Size(max = 255) String source
) {
}
