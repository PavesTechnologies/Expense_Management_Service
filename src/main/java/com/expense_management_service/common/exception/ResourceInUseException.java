package com.expense_management_service.common.exception;

/** Thrown when a resource cannot be deleted because other records still reference it. */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
