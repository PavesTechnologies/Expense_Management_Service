package com.expense_management_service.storage;

/**
 * Thrown when a {@link StorageService} operation fails against the backing object store.
 * Keeps callers (e.g. {@code ReceiptServiceImpl}) decoupled from AWS SDK-specific
 * exception types — only {@code storage} knows it's actually S3.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
