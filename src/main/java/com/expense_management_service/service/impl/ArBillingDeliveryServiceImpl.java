package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.config.ArBillingMessagingConfig;
import com.expense_management_service.dto.external.ArBillingMessage;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.enums.ArDeliveryStatus;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.InvoiceSyncRepository;
import com.expense_management_service.service.ArBillingDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Publishes each handoff to AR's queue and waits for the broker's publisher confirm, so SENT means RabbitMQ has
 * the message - not merely that XMS tried. Any failure (broker down, confirm timeout, a nack) leaves the handoff
 * FAILED with the error, for {@code ArBillingDeliveryRetryScheduler} to retry up to
 * {@code invoice-handoff.ar-delivery.max-attempts}, after which Finance resends it by hand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArBillingDeliveryServiceImpl implements ArBillingDeliveryService {

    /** A PENDING handoff older than this was never sent right after its commit; the retry job picks it up. */
    private static final long PENDING_GRACE_MINUTES = 2;

    private final InvoiceSyncRepository invoiceSyncRepository;
    private final InvoiceSyncMapper invoiceSyncMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Value("${invoice-handoff.ar-delivery.max-attempts:10}")
    private int maxAttempts;

    @Value("${invoice-handoff.ar-delivery.confirm-timeout-ms:10000}")
    private long confirmTimeoutMs;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InvoiceSyncResponse deliver(UUID syncId) {
        InvoiceSync sync = findHandoff(syncId);
        if (sync.getArDeliveryStatus() != ArDeliveryStatus.SENT) {
            send(sync);
        }
        return invoiceSyncMapper.toResponse(sync);
    }

    @Override
    @Transactional
    public InvoiceSyncResponse redeliver(UUID syncId) {
        InvoiceSync sync = findHandoff(syncId);
        if (sync.getArDeliveryStatus() == ArDeliveryStatus.SENT) {
            throw new BusinessRuleViolationException("Handoff " + syncId + " already reached AR on " + sync.getArDeliveredAt()
                    + ". To bill it again, hand the line off again with a reason.");
        }
        send(sync);
        return invoiceSyncMapper.toResponse(sync);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueForDelivery() {
        return invoiceSyncRepository.findDueForArDelivery(InvoiceHandoffServiceImpl.STATUS_HANDED_OFF,
                ArDeliveryStatus.FAILED, ArDeliveryStatus.PENDING, maxAttempts,
                LocalDateTime.now().minusMinutes(PENDING_GRACE_MINUTES));
    }

    private void send(InvoiceSync sync) {
        LocalDateTime now = LocalDateTime.now();
        int attempt = (sync.getArDeliveryAttempts() != null ? sync.getArDeliveryAttempts() : 0) + 1;
        sync.setArDeliveryAttempts(attempt);
        sync.setArLastAttemptAt(now);
        try {
            ArBillingMessage message = toMessage(sync);
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(ArBillingMessagingConfig.EXCHANGE_NAME, ArBillingMessagingConfig.ROUTING_KEY, message);
                operations.waitForConfirmsOrDie(confirmTimeoutMs);
                return null;
            });
            sync.setArDeliveryStatus(ArDeliveryStatus.SENT);
            sync.setArDeliveredAt(now);
            sync.setArDeliveryError(null);
            log.info("Handoff {} delivered to AR (attempt {})", sync.getSyncId(), attempt);
        } catch (RuntimeException ex) {
            sync.setArDeliveryStatus(ArDeliveryStatus.FAILED);
            sync.setArDeliveryError(describe(ex));
            log.warn("Handoff {} could not be delivered to AR (attempt {} of {}): {}",
                    sync.getSyncId(), attempt, maxAttempts, sync.getArDeliveryError());
        }
        invoiceSyncRepository.save(sync);
    }

    private ArBillingMessage toMessage(InvoiceSync sync) {
        String remarks = sync.getRemarks();
        boolean reprocess = remarks != null && remarks.startsWith(InvoiceHandoffServiceImpl.REPROCESS_REMARK_PREFIX);
        return new ArBillingMessage(ArBillingMessage.MESSAGE_VERSION, sync.getSyncId(), reprocess,
                reprocess ? remarks.substring(InvoiceHandoffServiceImpl.REPROCESS_REMARK_PREFIX.length()) : null,
                sync.getSyncDate(),
                BillingPayloadFactory.build(sync.getLineItem(), baseCurrencyCode,
                        InvoiceHandoffServiceImpl.STATUS_HANDED_OFF, sync.getInvoiceReference()));
    }

    private InvoiceSync findHandoff(UUID syncId) {
        InvoiceSync sync = invoiceSyncRepository.findById(syncId)
                .orElseThrow(() -> new ResourceNotFoundException("Handoff not found with id: " + syncId));
        if (!InvoiceHandoffServiceImpl.STATUS_HANDED_OFF.equals(sync.getSyncStatus())) {
            throw new BusinessRuleViolationException("Record " + syncId + " is not a handoff, so there is nothing to send to AR");
        }
        return sync;
    }

    private static String describe(RuntimeException ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
