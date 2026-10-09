package com.expense_management_service.service;

/**
 * Thrown when a {@link TextractService} operation fails against AWS Textract.
 * Keeps callers decoupled from AWS SDK-specific exception types — only this
 * integration point knows it's actually Textract.
 */
public class TextractIntegrationException extends RuntimeException {

    public TextractIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public TextractIntegrationException(String message) {
        super(message);
    }
}
