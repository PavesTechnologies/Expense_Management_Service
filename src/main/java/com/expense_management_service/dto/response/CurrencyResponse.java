package com.expense_management_service.dto.response;

import java.util.UUID;

public record CurrencyResponse(
        UUID currencyId,
        String currencyCode,
        String currencyName,
        String symbol,
        Integer decimalPlaces,
        String status
) {
}
