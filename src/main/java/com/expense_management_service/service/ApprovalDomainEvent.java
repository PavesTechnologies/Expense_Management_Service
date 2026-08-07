package com.expense_management_service.service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-process Spring application event raised by {@code ApprovalWorkflowServiceImpl} inside its own
 * transaction. A separate {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listener is what
 * actually publishes this to RabbitMQ - so an event is never emitted for a transition that ended up
 * rolling back, while the transport itself (§7.1) is real AMQP, not in-process delivery.
 */
public record ApprovalDomainEvent(String eventType, UUID reportId, String detail, LocalDateTime occurredAt) {

    public ApprovalDomainEvent(String eventType, UUID reportId, String detail) {
        this(eventType, reportId, detail, LocalDateTime.now());
    }
}
