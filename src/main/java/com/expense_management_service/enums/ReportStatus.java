package com.expense_management_service.enums;

/**
 * Lifecycle states for {@code ExpenseReport.reportStatus}.
 * <p>
 * The transition out of PENDING_APPROVAL on rejection (back to DRAFT vs. a
 * dedicated correction state) is still an open question - see EP06 plan,
 * "correction loop" (S2) is unspecified in the source tracker.
 */
public enum ReportStatus {
    DRAFT,
    SUBMITTED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CANCELLED,
    REIMBURSED
}
