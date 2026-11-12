package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record PolicyRuleLimitRequest(
        @NotNull UUID currencyId,
        @NotNull BigDecimal limitAmount
) {
}
