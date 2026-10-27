package com.expense_management_service.service;

import java.util.UUID;

/**
 * The seam between the Approval Workflow Engine and everything downstream that reacts to its state
 * changes (WebSocket push, notifications, audit). The orchestrator calls this after every transition;
 * it never knows or cares what is consuming these events.
 */
public interface ApprovalEventPublisher {

    void publish(String eventType, UUID reportId, String detail);
}
