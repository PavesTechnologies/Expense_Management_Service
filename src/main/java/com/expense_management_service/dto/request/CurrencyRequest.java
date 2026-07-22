package com.expense_management_service.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CurrencyRequest(
        @NotBlank @Size(max = 255) String currencyCode,
        @NotBlank @Size(max = 255) String currencyName,
        @Size(max = 255) String symbol,
        @NotNull @Min(0) Integer decimalPlaces,
        @Size(max = 255) String status
) {
}
