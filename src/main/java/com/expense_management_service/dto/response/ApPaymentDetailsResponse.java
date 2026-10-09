package com.expense_management_service.dto.response;

import java.util.List;

/**
 * AP payment details view - composed entirely from existing DTOs (no invented fields): the report
 * itself (includes payment routing status, cost center, currency, approved amount), its line
 * items, and the same approval-status read model the Manager/Finance screens already use.
 */
public record ApPaymentDetailsResponse(
        ExpenseReportResponse report,
        List<ExpenseLineItemResponse> lineItems,
        ApprovalStatusResponse approvalStatus
) {
}
