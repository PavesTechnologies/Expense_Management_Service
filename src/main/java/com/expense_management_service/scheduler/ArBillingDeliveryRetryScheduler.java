package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.service.ArBillingDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Resends handoffs that haven't reached AR: FAILED ones under the attempt limit, and PENDING ones whose send
 * never ran. Cadence configurable via {@code invoice-handoff.ar-delivery.retry-cron} (default: every 5 minutes).
 * Each handoff is sent in its own transaction, so one failure never holds up the rest.
 *
 * <p>Runs as {@code SYSTEM} - nothing here depends on a request-bound {@code SecurityContext}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArBillingDeliveryRetryScheduler {

    private final ArBillingDeliveryService arBillingDeliveryService;

    @Scheduled(cron = "${invoice-handoff.ar-delivery.retry-cron:0 */5 * * * *}")
    public void retry() {
        List<UUID> due = arBillingDeliveryService.findDueForDelivery();
        if (due.isEmpty()) {
            return;
        }
        int sent = 0;
        for (UUID syncId : due) {
            try {
                InvoiceSyncResponse result = arBillingDeliveryService.deliver(syncId);
                if ("SENT".equals(result.arDeliveryStatus())) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.warn("AR delivery retry of handoff {} failed", syncId, ex);
            }
        }
        log.info("AR delivery retry: {} of {} handoff(s) delivered", sent, due.size());
    }
}
