package com.expense_management_service.dto.response;

/**
 * The employee's identity for the request form. Read-only: the employee cannot change any of it from the form.
 * A name that is not known is null, so the screen can hide it rather than show an ID.
 */
public record EmployeeProfileResponse(
        String employeeId,
        String fullName,
        String managerEmployeeId,
        String managerName,
        String departmentName,
        String designationName
) {
}
