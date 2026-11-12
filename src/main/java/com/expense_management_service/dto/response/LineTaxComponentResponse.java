package com.expense_management_service.dto.response;

import java.math.BigDecimal;

public record LineTaxComponentResponse(
        String componentCode,
        String label,
        BigDecimal ratePercent,
        BigDecimal taxAmount,
        /** Null in a calculate preview (no exchange rate involved). */
        BigDecimal baseTaxAmount,
        String source
) {
}
