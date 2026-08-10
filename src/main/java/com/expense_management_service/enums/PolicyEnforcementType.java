package com.expense_management_service.enums;

/**
 * Whether a rule's violation merely flags (never prevents anything) or hard-stops submission. A
 * separate axis from {@link PolicySeverity} — this is an Admin policy choice made per rule, not a
 * computed fact about how far over a limit a given expense is, so the two are never conflated on
 * {@code PolicyRule}/{@code PolicyViolation}.
 */
public enum PolicyEnforcementType {
    /** Shown to the employee and approver; never prevents a save or a submission. */
    WARN,
    /** Prevents {@code ApprovalWorkflowService.submit()} until the employee resolves it. Never blocks a line-item save. */
    BLOCK
}
