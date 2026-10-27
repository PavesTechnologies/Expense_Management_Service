package com.expense_management_service.integration.email;

/**
 * Default transport when no mail server is configured. It never reports success, so messages remain PENDING in the
 * outbox rather than being marked sent.
 */
public class UnconfiguredEmailTransport implements EmailTransport {

    @Override
    public void send(String to, String subject, String body) {
        throw new EmailTransportException("No mail transport is configured; the message stays queued.");
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
