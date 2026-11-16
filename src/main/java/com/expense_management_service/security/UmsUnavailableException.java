package com.expense_management_service.security;

/**
 * UMS could not be reached to load its signing configuration (discovery or key set). The request is not
 * authenticated, and the client should retry later. This is not a judgement on the caller's token.
 */
public class UmsUnavailableException extends RuntimeException {

    public UmsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
