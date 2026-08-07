package com.expense_management_service.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states for {@code ExpenseReport.reportStatus}, owned by the Approval Flow Engine
 * (replaces EP06's matrix-based engine).
 * <p>
 * {@code SUBMITTED}, {@code MANAGER_APPROVED}, {@code FINANCE_APPROVED} were removed outright in the
 * Approval Flow Engine rewrite: {@code SUBMITTED} was never actually used (submission goes straight
 * to {@code PENDING_APPROVAL}), and the other two were never set by anything (confirmed dead in the
 * old engine's own javadoc) and hardcode business labels ("Manager"/"Finance") that don't fit a
 * generic N-level flow where a level can be named anything. See {@code V6__approval_flow_engine_cleanup.sql}
 * for the one-time data cleanup this required.
 * <p>
 * {@code AWAITING_CORRECTION} is new: an approver flagged one or more line items as needing a fix
 * (comment required) without rejecting the whole report. Non-terminal - the employee corrects just
 * the flagged lines and it returns to the same approver, resuming in place unless the edit changes
 * which flow now matches (in which case the whole chain restarts).
 * <p>
 * {@code REJECTED} is now genuinely terminal - a whole-report, final business decision (fraud,
 * duplicate, wrong report), distinct from {@code AWAITING_CORRECTION}'s normal correction cycle. No
 * resubmission path exists from it; the employee must create a brand-new report.
 * <p>
 * {@code POLICY_REJECTED} and {@code QUERY_RAISED} remain automated pre-approval states that occur
 * before a report ever reaches this engine - not owned or transitioned by it. {@code CLOSED} and
 * {@code REIMBURSED} belong to Reimbursement Tracking's lifecycle, downstream of this engine's
 * {@code APPROVED} handoff.
 */
public enum ReportStatus {
    DRAFT,
    PENDING_APPROVAL,
    /** Non-terminal: ≥1 line item flagged Needs Correction; other lines' approvals are preserved. */
    AWAITING_CORRECTION,
    POLICY_REJECTED,
    QUERY_RAISED,
    APPROVED,
    /** Terminal - a final business decision, not a correction cycle. No resubmission path. */
    REJECTED,
    /** Terminal - employee-initiated abandon, distinct from Recall (which returns to DRAFT). */
    CANCELLED,
    CLOSED,
    REIMBURSED;

    private static final Set<ReportStatus> EDITABLE = EnumSet.of(DRAFT, POLICY_REJECTED, QUERY_RAISED, AWAITING_CORRECTION);
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
