package com.expense_management_service.service.impl;

import com.expense_management_service.config.ApprovalEventingConfig;
import com.expense_management_service.service.ApprovalDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes to the real RabbitMQ transport only after the transaction that raised the event has
 * committed - a rolled-back transaction never emits an event onto the queue. Deliberately
 * fire-and-forget: a broker outage here must never affect the Approval Engine's own transaction,
 * which has already committed by the time this runs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitApprovalEventListener {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalEvent(ApprovalDomainEvent event) {
        try {
            rabbitTemplate.convertAndSend(ApprovalEventingConfig.EXCHANGE_NAME, "approval." + event.eventType().toLowerCase(), event);
        } catch (Exception ex) {
            log.warn("Failed to publish approval event {} for report {} to RabbitMQ - workflow progress is unaffected",
                    event.eventType(), event.reportId(), ex);
        }
    }
}
