package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxCodeComponentResponse(
        UUID componentId,
        String componentCode,
        String label,
        BigDecimal ratePercent,
        Integer sequence,
        UUID glAccountId,
        String glAccountName
) {
}
