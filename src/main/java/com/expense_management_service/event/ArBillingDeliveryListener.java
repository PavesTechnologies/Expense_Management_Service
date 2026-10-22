package com.expense_management_service.event;

import com.expense_management_service.service.ArBillingDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a handoff to AR right after the handoff commits, so a handoff that rolled back is never sent. The handoff
 * itself has already succeeded by now: if this send fails, the record stays FAILED (or PENDING, if the service
 * stops first) and the retry job sends it later.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArBillingDeliveryListener {

    private final ArBillingDeliveryService arBillingDeliveryService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onHandoffRecorded(InvoiceHandoffRecordedEvent event) {
        try {
            arBillingDeliveryService.deliver(event.syncId());
        } catch (RuntimeException ex) {
            log.warn("Delivery of handoff {} to AR failed before it was recorded - the retry job will send it", event.syncId(), ex);
        }
    }
}
