package com.expense_management_service.common.exception;

/** Thrown when a requested XMS resource does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
