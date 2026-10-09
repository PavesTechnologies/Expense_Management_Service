package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record PolicyRuleLimitResponse(
        UUID currencyId,
        String currencyCode,
        BigDecimal limitAmount
) {
}
