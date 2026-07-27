package com.expense_management_service.common.exception;

/**
 * Thrown when the currently authenticated employee is marked Inactive in UMS and
 * attempts an action reserved for Active employees (e.g. creating an expense report).
 * The caller should be redirected to HR contact information rather than retrying.
 */
public class EmployeeInactiveException extends RuntimeException {

    public EmployeeInactiveException(String message) {
        super(message);
    }
}
