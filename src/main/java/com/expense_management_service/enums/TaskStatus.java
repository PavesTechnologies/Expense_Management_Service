package com.expense_management_service.enums;

/**
 * Lifecycle states for {@code ApprovalTask.taskStatus}.
 * <p>
 * QUEUED and SKIPPED exist specifically to support the "snapshot at
 * submission" design: every level in a resolved approval chain is
 * materialised as a row immediately, with future levels QUEUED (dormant,
 * no assignedAt/dueDate yet) and duplicate-approver levels SKIPPED - rather
 * than being created only when a level is actually reached.
 */
public enum TaskStatus {
    /** Materialised as part of the chain, but not yet this level's turn - assignedAt/dueDate are null. */
    QUEUED,
    /** Active and awaiting the approver's (or an active delegate's) action. */
    PENDING,
    APPROVED,
    REJECTED,
    /** Auto-skipped because the resolved approver already appears earlier in the same chain. */
    SKIPPED,
    /** A sibling in an ALL-required parallel group was rejected, or the report was withdrawn. */
    CANCELLED,
    /** Reassigned after an SLA breach; the task stays PENDING under the new approver, not this state. */
    ESCALATED
}
