package com.expense_management_service.enums;

/**
 * Lifecycle of one resolved {@code ApprovalAssignment} (one approver-source entry, resolved to an
 * actual employeeId) within an {@code ApprovalLevelInstance}.
 */
public enum AssignmentStatus {
    /** Resolved, but this level isn't active yet (relevant to SEQUENTIAL entries not yet up). */
    PENDING,
    /** This assignment can currently act. */
    ACTIVE,
    /** This assignee has finished acting on every line item they were asked to review. */
    COMPLETED,
    /** Auto-skipped: this resolved employeeId already appears earlier in the same chain (§2.6 dedup). */
    SKIPPED,
    /** Replaced by a fresh assignment after the original approver's account was removed (§5.5). */
    SUPERSEDED
}
