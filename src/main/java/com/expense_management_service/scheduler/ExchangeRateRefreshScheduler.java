package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automated exchange-rate refresh (EP01-S3). The cadence is configurable via
 * {@code exchange-rate.refresh.cron} (default: hourly). Delegates entirely to
 * {@link ExchangeRateService#refreshRates()} — this class only owns the trigger/cadence and logging.
 * <p>
 * The refresh is idempotent per day (pairs already recorded for today are skipped without calling the
 * provider), so running it hourly and once on startup means a day is only missed if the service is
 * down, or the provider is unreachable, for the whole day — a single fixed daily run left a gap
 * whenever the service happened to be down or the API call failed at that one moment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateRefreshScheduler {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(cron = "${exchange-rate.refresh.cron:0 0 * * * *}")
    public void refresh() {
        runRefresh("scheduled");
    }

    /** Catch-up run so a restart or redeploy records today's rates without waiting for the next cron tick. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        runRefresh("startup");
    }

    private void runRefresh(String trigger) {
        log.info("Starting {} exchange rate refresh", trigger);
        try {
            ExchangeRateRefreshResponse result = exchangeRateService.refreshRates();
            log.info("{} exchange rate refresh completed: {}", trigger, result);
        } catch (RuntimeException ex) {
            log.error("{} exchange rate refresh failed; it will be retried on the next run", trigger, ex);
        }
    }
}
