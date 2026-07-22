package com.expense_management_service.integration.ums.dto;

import java.util.UUID;

/**
 * Shape of a UMS employee record response.
 * <p>
 * Only the fields commonly needed by an expense module are modeled here;
 * extend as XMS requires more employee attributes (e.g. department, cost center).
 */
public record EmployeeResponse(
        UUID uuid,
        String employeeId,
        String name,
        String email,
        String department,
        String designation
) {
}
