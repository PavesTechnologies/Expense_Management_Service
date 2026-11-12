package com.expense_management_service.service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * In-process Spring application event raised by {@code ApprovalWorkflowServiceImpl} inside its own
 * transaction. Its consumers (WebSocket push, notifications) are {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} listeners, so nothing reacts to a transition that ended up rolling back.
 */
public record ApprovalDomainEvent(String eventType, UUID reportId, String detail, LocalDateTime occurredAt) {

    public ApprovalDomainEvent(String eventType, UUID reportId, String detail) {
        this(eventType, reportId, detail, LocalDateTime.now());
    }
}
