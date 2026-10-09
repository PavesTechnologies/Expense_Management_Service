package com.expense_management_service.enums;

/**
 * The actual per-line-item decision within one FINANCE_VERIFICATION {@code ApprovalLevelInstance} -
 * the Finance-level equivalent of {@code LineItemReviewStatus}, kept as a separate enum/table
 * because Finance review carries additional GL/policy/receipt fields a Manager review never needs.
 */
public enum FinanceVerificationStatus {
    PENDING,
    VERIFIED,
    /** Non-terminal - Finance flagged this line for clarification/correction without rejecting the whole report. */
    QUERIED
}
