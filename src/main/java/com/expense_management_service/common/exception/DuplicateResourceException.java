package com.expense_management_service.common.exception;

/** Thrown when a request would violate a business-level uniqueness rule (e.g. a duplicate code). */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
