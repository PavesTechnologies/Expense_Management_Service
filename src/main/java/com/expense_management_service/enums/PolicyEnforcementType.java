package com.expense_management_service.enums;

/**
 * Whether a rule's violation merely flags (never prevents anything) or hard-stops submission. A
 * separate axis from {@link PolicySeverity} — this is an Admin policy choice made per rule, not a
 * computed fact about how far over a limit a given expense is, so the two are never conflated on
 * {@code PolicyRule}/{@code PolicyViolation}.
 */
public enum PolicyEnforcementType {
    /** Shown to the employee and approver; the employee explains it when submitting. Never prevents a save. */
    WARN,
    /**
     * Marks the line item BLOCKED and prevents {@code ApprovalWorkflowService.submit()} until the
     * employee either brings the line back within policy or explains the violation. An explained
     * BLOCK violation is submitted, and the approver and Finance decide whether to accept it.
     * Never blocks a line-item save.
     */
    BLOCK
}
