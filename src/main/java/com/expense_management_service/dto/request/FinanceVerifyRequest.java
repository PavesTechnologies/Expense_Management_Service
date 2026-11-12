package com.expense_management_service.dto.request;

/** Optional body of the verify action. {@code taxChecked} is required for lines flagged MISMATCH / REQUIRES_FINANCE_REVIEW. */
public record FinanceVerifyRequest(Boolean taxChecked) {
}
