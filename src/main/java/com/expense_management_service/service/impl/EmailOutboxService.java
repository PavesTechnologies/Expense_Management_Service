package com.expense_management_service.service.impl;

import com.expense_management_service.entity.OutboundEmail;
import com.expense_management_service.integration.email.EmailTransport;
import com.expense_management_service.integration.email.EmailTransportException;
import com.expense_management_service.repository.OutboundEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queues outgoing email and sends it through the configured transport. A message that the transport rejects is kept
 * for the next run; after {@link #MAX_ATTEMPTS} rejections it is marked FAILED and not retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutboxService {

    static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 100;

    private final OutboundEmailRepository outboundEmailRepository;
    private final EmailTransport emailTransport;

    @Transactional
    public OutboundEmail enqueue(String to, String subject, String body, String emailType, String referenceId) {
        OutboundEmail email = OutboundEmail.builder()
                .toAddress(to)
                .subject(subject)
                .body(body)
                .emailType(emailType)
                .referenceId(referenceId)
                .status(OutboundEmail.PENDING)
                .attempts(0)
                .createdAt(LocalDateTime.now())
                .build();
        return outboundEmailRepository.save(email);
    }

    /**
     * Withdraws a message that has not been sent yet. Returns the status the message has afterwards: CANCELLED when it
     * was withdrawn, otherwise the status it already had (SENT, FAILED or CANCELLED). Callers use the result to decide
     * whether Payroll may already have received the email.
     */
    @Transactional
    public String cancel(UUID outboundEmailId) {
        OutboundEmail email = outboundEmailRepository.findByIdForUpdate(outboundEmailId)
                .orElseThrow(() -> new IllegalArgumentException("Outbound email not found: " + outboundEmailId));
        if (OutboundEmail.PENDING.equals(email.getStatus())) {
            email.setStatus(OutboundEmail.CANCELLED);
        }
        return email.getStatus();
    }

    @Transactional(readOnly = true)
    public Optional<OutboundEmail> find(UUID outboundEmailId) {
        return outboundEmailRepository.findById(outboundEmailId);
    }

    /** True when a message for this type and reference is queued or already delivered. Used to dedupe sends. */
    @Transactional(readOnly = true)
    public boolean hasLiveMessage(String emailType, String referenceId) {
        return outboundEmailRepository.findByEmailTypeAndReferenceId(emailType, referenceId).stream()
                .anyMatch(e -> OutboundEmail.PENDING.equals(e.getStatus()) || OutboundEmail.SENT.equals(e.getStatus()));
    }

    /** Sends up to one batch of pending messages. Returns how many were sent in this run. */
    @Transactional
    public int dispatchPending() {
        if (!emailTransport.isConfigured()) {
            // No mail server: keep everything queued. Attempts are not used up, so nothing is marked FAILED.
            return 0;
        }
        List<OutboundEmail> batch = outboundEmailRepository.findByStatusOrderByCreatedAtAsc(
                OutboundEmail.PENDING, PageRequest.of(0, BATCH_SIZE));
        int sent = 0;
        for (OutboundEmail email : batch) {
            try {
                emailTransport.send(email.getToAddress(), email.getSubject(), email.getBody());
                email.setStatus(OutboundEmail.SENT);
                email.setSentAt(LocalDateTime.now());
                email.setLastError(null);
                sent++;
            } catch (EmailTransportException e) {
                email.setAttempts(email.getAttempts() + 1);
                email.setLastError(truncate(e.getMessage()));
                if (email.getAttempts() >= MAX_ATTEMPTS) {
                    email.setStatus(OutboundEmail.FAILED);
                    log.warn("Email {} marked FAILED after {} attempts", email.getOutboundEmailId(), email.getAttempts());
                }
            }
        }
        return sent;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
