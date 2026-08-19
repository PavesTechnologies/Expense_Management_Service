package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One line item pending Finance action - {@code eligibleForVerify}/{@code ineligibleReason} are backed by the same {@code FinanceVerificationEligibilityChecker} VERIFY itself uses, so the queue and the action can never disagree. */
public record FinancePendingLineItemResponse(
        UUID lineItemId,
        UUID reviewId,
        String categoryName,
        String merchantName,
        String description,
        LocalDate expenseDate,
        BigDecimal amount,
        String currencyCode,
        String glAccountCode,
        boolean eligibleForVerify,
        String ineligibleReason,
        boolean clientBillable
) {
}
