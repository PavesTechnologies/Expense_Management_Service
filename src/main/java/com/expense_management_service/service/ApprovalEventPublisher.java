package com.expense_management_service.service;

import java.util.UUID;

/**
 * The seam between the Approval Workflow Engine and everything downstream that reacts to its state
 * changes (Notification/Audit, §7 - deferred in detail, but the mechanism is a real RabbitMQ
 * producer, not in-process). The orchestrator calls this after every committed transition; it never
 * knows or cares whether anything is actually consuming these events yet.
 */
public interface ApprovalEventPublisher {

    void publish(String eventType, UUID reportId, String detail);
}
