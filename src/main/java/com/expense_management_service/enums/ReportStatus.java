package com.expense_management_service.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states for {@code ExpenseReport.reportStatus}.
 * <p>
 * Two vocabularies merged here: EP06's approval-engine states (SUBMITTED, PENDING_APPROVAL,
 * APPROVED, REJECTED, CANCELLED - set by {@code ApprovalWorkflowService}, which resolves an
 * N-level {@code ApprovalMatrix} chain generically) and EP02-S1's richer status set
 * (MANAGER_APPROVED, POLICY_REJECTED, QUERY_RAISED, FINANCE_APPROVED, CLOSED - originally
 * modelled as plain String constants in the now-removed {@code ReportStatusConstants}).
 * <p>
 * POLICY_REJECTED and QUERY_RAISED are automated pre-approval states (e.g. a policy-rule
 * check) that occur before a report ever reaches {@code ApprovalWorkflowService.submit()} -
 * they are not something the approval engine itself transitions into or out of. Nothing
 * currently sets MANAGER_APPROVED/FINANCE_APPROVED/CLOSED; they exist as valid values for
 * that pre/post-approval machinery to use once built, without forcing the tested N-level
 * engine to guess a business label from a level index.
 */
public enum ReportStatus {
    DRAFT,
    SUBMITTED,
    PENDING_APPROVAL,
    MANAGER_APPROVED,
    POLICY_REJECTED,
    QUERY_RAISED,
    FINANCE_APPROVED,
    APPROVED,
    REJECTED,
    CANCELLED,
    CLOSED,
    REIMBURSED;

    private static final Set<ReportStatus> EDITABLE = EnumSet.of(DRAFT, POLICY_REJECTED, QUERY_RAISED);
    private static final Set<ReportStatus> DELETABLE = EnumSet.of(DRAFT);

    /** Statuses in which the report (and its line items/receipts) may still be edited by its owner. */
    public boolean isEditable() {
        return EDITABLE.contains(this);
    }

    /** Statuses in which the report may be deleted outright by its owner. */
    public boolean isDeletable() {
        return DELETABLE.contains(this);
    }
}
