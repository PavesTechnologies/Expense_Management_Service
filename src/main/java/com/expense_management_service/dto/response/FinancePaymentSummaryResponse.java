package com.expense_management_service.dto.response;

/**
 * Reimbursement progress of finance-verified reports, for the Finance page's summary cards.
 * Payment routing is only ever set on a report whose flow had a Finance Verification level
 * (see {@code ApprovalWorkflowServiceImpl.applyPaymentRouting}), so these counts are exactly the
 * reports Finance has verified.
 */
public record FinancePaymentSummaryResponse(
        long awaitingPaymentCount,
        long paidCount
) {
}
