package com.expense_management_service.dto.response;

import java.math.BigDecimal;

/** Headline numbers for the Client Billing page. Amounts are in the organization base currency. */
public record InvoiceHandoffSummaryResponse(
        long readyCount,
        BigDecimal readyBaseAmount,
        long readyProjectCount,
        long handedOffCount,
        String baseCurrencyCode
) {
}
