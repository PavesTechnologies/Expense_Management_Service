package com.expense_management_service.dto.response;

import java.math.BigDecimal;

/** Headline numbers for the Client Billing page. Amounts are in the organization base currency. */
public record InvoiceHandoffSummaryResponse(
        long readyCount,
        BigDecimal readyBaseAmount,
        long readyProjectCount,
        long handedOffCount,
        String baseCurrencyCode,
        /** Distinct clients among the ready line items — the unit AR invoices by. */
        long readyClientCount,
        /** Handoffs whose last delivery to AR failed - retried automatically, or resent by Finance. */
        long arDeliveryFailedCount
) {
    /** Shape before delivery to AR was tracked. */
    public InvoiceHandoffSummaryResponse(long readyCount, BigDecimal readyBaseAmount, long readyProjectCount,
                                         long handedOffCount, String baseCurrencyCode, long readyClientCount) {
        this(readyCount, readyBaseAmount, readyProjectCount, handedOffCount, baseCurrencyCode, readyClientCount, 0);
    }
}
