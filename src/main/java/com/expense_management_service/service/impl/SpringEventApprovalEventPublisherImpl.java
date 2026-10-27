package com.expense_management_service.service.impl;

import com.expense_management_service.service.ApprovalDomainEvent;
import com.expense_management_service.service.ApprovalEventPublisher;
import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The orchestrator-facing side of event emission: raises a plain in-process Spring event, inside
 * the same transaction as the state change that caused it. Listeners pick it up from there.
 */
@Component
@RequiredArgsConstructor
public class SpringEventApprovalEventPublisherImpl implements ApprovalEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(String eventType, UUID reportId, String detail) {
        applicationEventPublisher.publishEvent(new ApprovalDomainEvent(eventType, reportId, detail));
    }
}
