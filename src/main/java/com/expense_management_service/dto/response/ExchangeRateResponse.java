package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExchangeRateResponse(
        UUID exchangeRateId,
        UUID fromCurrencyId,
        String fromCurrencyCode,
        UUID toCurrencyId,
        String toCurrencyCode,
        BigDecimal rate,
        LocalDate effectiveDate,
        String source,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime fetchedAt
) {
}
