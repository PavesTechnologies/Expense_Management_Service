package com.expense_management_service.enums;

/**
 * How {@code ApprovalMatrix.approverReference} should be resolved to an actual approver.
 */
public enum ApproverType {
    /** approverReference is a direct employeeId, used as-is. */
    USER,
    /** approverReference is ignored; the approver is CostCenter.ownerEmployeeId. */
    COST_CENTER_OWNER,
    /** approverReference is ignored; the approver is EmployeeCache.managerEmployeeId for the submitting employee. */
    MANAGER,
    /** approverReference is a role name, resolved via a pluggable ApproverResolver (see EP06 plan, Phase 2). */
    ROLE
}
