package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The carry-forward ready on an advance: the amount that will be added to the employee's next approved advance, and
 * the advance's outstanding balance at the time. {@code readyAmount} is null when nothing is ready.
 */
public record CarryForwardReadyResponse(
        UUID advanceId,
        String currencyCode,
        BigDecimal readyAmount,
        BigDecimal outstandingBalance
) {
}
