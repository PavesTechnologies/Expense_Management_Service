package com.expense_management_service.enums;

/**
 * How an {@code ApprovalLevelApprover} entry resolves to an actual acting approver. A level names a
 * SOURCE, never a fixed person directly (except {@code NAMED_USER}) - this is what lets a flow
 * survive org churn instead of needing an edit every time someone changes role.
 */
public enum ApproverSourceType {
    /** {@code sourceReference} is a direct EOS employeeId, used as-is. */
    NAMED_USER,
    /** {@code sourceReference} is ignored; resolves to {@code EmployeeCache.managerEmployeeId} for the submitting employee. */
    REPORTING_MANAGER,
    /** {@code sourceReference} is ignored; resolves via {@code DepartmentApprover} for the submitter's {@code EmployeeCache.departmentUuid}. */
    DEPARTMENT_OWNER,
    /** {@code sourceReference} is ignored; resolves to the report's {@code CostCenter.ownerEmployeeId}. */
    COST_CENTER_OWNER
}
