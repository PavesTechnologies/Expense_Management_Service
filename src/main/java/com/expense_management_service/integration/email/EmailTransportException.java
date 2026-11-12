package com.expense_management_service.integration.email;

/** The transport did not accept the message. The outbox keeps the message for a later attempt. */
public class EmailTransportException extends RuntimeException {

    public EmailTransportException(String message) {
        super(message);
    }
}
