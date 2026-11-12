package com.expense_management_service.common.exception;

/** Another service XMS depends on (Employee Onboarding, UMS ...) could not be reached or failed. Mapped to 502. */
public class IntegrationUnavailableException extends RuntimeException {

    public IntegrationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
