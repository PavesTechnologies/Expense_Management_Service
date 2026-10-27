package com.expense_management_service.integration.email;

/**
 * Sends one email. The application ships with {@link UnconfiguredEmailTransport}, which refuses to send, so mail stays
 * queued in {@code outbound_email} until a real transport bean (for example an SMTP implementation) is added.
 */
public interface EmailTransport {

    /** @throws EmailTransportException when the message could not be accepted by the transport */
    void send(String to, String subject, String body);

    /**
     * False when no mail server is configured. Messages then stay queued and are not counted as failed attempts,
     * so they are sent once a transport is configured.
     */
    default boolean isConfigured() {
        return true;
    }
}
