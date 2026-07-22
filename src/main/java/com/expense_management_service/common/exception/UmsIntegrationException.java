package com.expense_management_service.common.exception;

/** Thrown when a call from XMS to UMS fails (network error, non-2xx response, etc.). */
public class UmsIntegrationException extends RuntimeException {

    public UmsIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public UmsIntegrationException(String message) {
        super(message);
    }
}
