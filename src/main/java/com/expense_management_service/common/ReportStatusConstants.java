package com.expense_management_service.common;

import java.util.Set;

/**
 * Expense report workflow status values and the status-gated rules that apply to them.
 * <p>
 * {@code report_status} is a plain string column (matching the rest of XMS's status
 * columns), not a JPA enum — these constants are the single source of truth for valid
 * values until a dedicated workflow module (approvals/submission) formalizes the machine.
 */
public final class ReportStatusConstants {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String MANAGER_APPROVED = "MANAGER_APPROVED";
    public static final String POLICY_REJECTED = "POLICY_REJECTED";
    public static final String QUERY_RAISED = "QUERY_RAISED";
    public static final String FINANCE_APPROVED = "FINANCE_APPROVED";
    public static final String REIMBURSED = "REIMBURSED";
    public static final String CLOSED = "CLOSED";

    /** Statuses in which the report (and its line items) may still be edited by its owner. */
    public static final Set<String> EDITABLE_STATUSES = Set.of(DRAFT, POLICY_REJECTED, QUERY_RAISED);

    /** Statuses in which the report may be deleted outright by its owner. */
    public static final Set<String> DELETABLE_STATUSES = Set.of(DRAFT);

    public static boolean isEditable(String status) {
        return status != null && EDITABLE_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT));
    }

    public static boolean isDeletable(String status) {
        return status != null && DELETABLE_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT));
    }

    private ReportStatusConstants() {
    }
}
