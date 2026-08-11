package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.EscalationRunResponse;
import com.expense_management_service.service.EscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic SLA reminder sweep (§5.4 - reminders-only, reconfirmed after full market research; no
 * auto-reassignment, unlike EP06). Cadence configurable via {@code escalation.sla.cron} (default:
 * hourly). Delegates entirely to {@link EscalationService#runReminderSweep()} - this class only
 * owns the trigger and logging.
 * <p>
 * Runs as SYSTEM - must never depend on {@code CurrentUserService} or {@code UmsClient}, both of
 * which require a request-bound {@code SecurityContext} that does not exist on this thread. Note
 * there is no dedicated {@code TaskScheduler} bean, so this shares a single-threaded pool with
 * {@code ExchangeRateRefreshScheduler} and {@code CdcRetryScheduler}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationScheduler {

    private final EscalationService escalationService;

    @Scheduled(cron = "${escalation.sla.cron:0 0 * * * *}")
    public void runSweep() {
        log.info("Starting scheduled SLA reminder sweep");
        EscalationRunResponse result = escalationService.runReminderSweep();
        log.info("Scheduled SLA reminder sweep completed: {}", result);
    }
}
