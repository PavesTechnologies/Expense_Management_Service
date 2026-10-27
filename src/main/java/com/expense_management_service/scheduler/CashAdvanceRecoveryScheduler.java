package com.expense_management_service.scheduler;

import com.expense_management_service.service.impl.CashAdvanceRecoveryService;
import com.expense_management_service.service.impl.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Two jobs: escalates recovery cases whose grace period has ended (Route 3), and sends queued email from the outbox.
 * Each job logs and continues on failure so one bad run does not stop the next.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CashAdvanceRecoveryScheduler {

    private final CashAdvanceRecoveryService recoveryService;
    private final EmailOutboxService emailOutbox;

    @Scheduled(cron = "${cash-advance.recovery.cron:0 0 * * * *}")
    public void escalateDueRecoveryCases() {
        try {
            int queued = recoveryService.escalateDueCases(LocalDateTime.now());
            if (queued > 0) {
                log.info("Recovery escalation queued {} case(s) for payroll report", queued);
            }
        } catch (Exception e) {
            log.error("Recovery escalation run failed", e);
        }
    }

    @Scheduled(fixedDelayString = "${expense.email.dispatch-delay-ms:300000}")
    public void dispatchQueuedEmail() {
        try {
            emailOutbox.dispatchPending();
        } catch (Exception e) {
            log.error("Email dispatch run failed", e);
        }
    }
}
