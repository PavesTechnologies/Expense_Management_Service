package com.expense_management_service.dto.response;

/** Headline counts for the AP Payments page. Counts only: reports can be in different currencies. */
public record ApPaymentSummaryResponse(
        long pendingCount,
        long paidThisMonthCount,
        long paidCount,
        long handoffFailedCount
) {
}
