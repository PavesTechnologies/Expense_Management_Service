package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.CdcRetryResponse;
import com.expense_management_service.service.CdcRetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically replays CDC events recorded in {@code cdc_failure_log} (EP06 Phase 0).
 * Cadence configurable via {@code cdc.retry.cron} (default: every 10 minutes, matching
 * the Leave Management System's reference cadence). Delegates entirely to
 * {@link CdcRetryService#retryFailedEvents()} - this class only owns the trigger and logging.
 *
 * <p>Runs as {@code SYSTEM} - it must never depend on {@code CurrentUserService} or
 * {@code UmsClient}, both of which require a request-bound {@code SecurityContext}
 * that does not exist on the scheduler thread.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CdcRetryScheduler {

    private final CdcRetryService cdcRetryService;

    @Scheduled(cron = "${cdc.retry.cron:0 */10 * * * *}")
    public void retry() {
        log.info("Starting scheduled CDC failure retry");
        CdcRetryResponse result = cdcRetryService.retryFailedEvents();
        log.info("Scheduled CDC failure retry completed: {}", result);
    }
}
