package com.expense_management_service.scheduler;

import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily automated exchange-rate refresh (EP01-S3). The cadence is configurable via
 * {@code exchange-rate.refresh.cron} (default: 02:00 server time). Delegates entirely to
 * {@link ExchangeRateService#refreshRates()} — this class only owns the trigger/cadence and logging.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateRefreshScheduler {

    private final ExchangeRateService exchangeRateService;

    @Scheduled(cron = "${exchange-rate.refresh.cron:0 0 2 * * *}")
    public void refresh() {
        log.info("Starting scheduled exchange rate refresh");
        ExchangeRateRefreshResponse result = exchangeRateService.refreshRates();
        log.info("Scheduled exchange rate refresh completed: {}", result);
    }
}
