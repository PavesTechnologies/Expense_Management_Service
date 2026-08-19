package com.expense_management_service.service.impl;

import com.expense_management_service.config.ApprovalEventingConfig;
import com.expense_management_service.service.ApprovalDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * Publishes to the real RabbitMQ transport only after the transaction that raised the event has
 * committed - a rolled-back transaction never emits an event onto the queue. Deliberately
 * fire-and-forget: a broker outage here must never affect the Approval Engine's own transaction,
 * which has already committed by the time this runs.
 * <p>
 * Finance Verification events use a {@code finance.*} routing key instead of {@code approval.*}
 * (still on the same exchange - "do not build an unnecessary messaging subsystem") so a future
 * invoice/accounting consumer can bind to Finance events specifically without filtering the whole
 * approval stream. Both prefixes are equally unconsumed today - see {@code ApprovalEventingConfig}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitApprovalEventListener {

    private static final Set<String> FINANCE_EVENT_TYPES = Set.of(
            "FINANCE_VERIFICATION_ACTIVATED", "LINE_ITEM_VERIFIED", "VERIFICATION_QUERY_RAISED",
            "VERIFICATION_QUERY_RESOLVED", "FINANCE_VERIFICATION_COMPLETED",
            "REPORT_APPROVED_FOR_PAYMENT", "REPORT_INVOICE_HANDOFF");

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApprovalEvent(ApprovalDomainEvent event) {
        try {
            String prefix = FINANCE_EVENT_TYPES.contains(event.eventType()) ? "finance." : "approval.";
            rabbitTemplate.convertAndSend(ApprovalEventingConfig.EXCHANGE_NAME, prefix + event.eventType().toLowerCase(), event);
        } catch (Exception ex) {
            log.warn("Failed to publish approval event {} for report {} to RabbitMQ - workflow progress is unaffected",
                    event.eventType(), event.reportId(), ex);
        }
    }
}
