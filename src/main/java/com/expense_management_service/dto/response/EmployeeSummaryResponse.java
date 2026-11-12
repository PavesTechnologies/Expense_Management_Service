package com.expense_management_service.dto.response;

/**
 * A current employee as offered by employee pickers (delegate, default approver). {@code employeeId}
 * is the EOS employee ID - the identifier approvals and delegations are assigned by.
 */
public record EmployeeSummaryResponse(
        String employeeId,
        String name,
        String email,
        String departmentUuid,
        String managerEmployeeId
) {
}
