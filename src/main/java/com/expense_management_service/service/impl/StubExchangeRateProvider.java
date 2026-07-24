package com.expense_management_service.service.impl;

import com.expense_management_service.service.ExchangeRateProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Placeholder {@link ExchangeRateProvider} used until a real Exchange Rate Service API is integrated.
 * <p>
 * It deliberately never fabricates a rate — it always returns empty and logs a warning, so the
 * refresh job's bookkeeping (processed/created/skipped counts, logging, error handling) is fully
 * exercised and testable without silently reporting made-up financial data as if it were real.
 * Replace this bean with a real HTTP-backed implementation of {@link ExchangeRateProvider} when a
 * live provider is available; no other class needs to change.
 */
@Component
@Slf4j
public class StubExchangeRateProvider implements ExchangeRateProvider {

    @Override
    public Optional<BigDecimal> fetchRate(String fromCurrencyCode, String toCurrencyCode) {
        log.warn("No live exchange-rate provider is configured — skipping rate fetch for {} -> {}. "
                        + "Replace StubExchangeRateProvider with a real ExchangeRateProvider implementation to enable this.",
                fromCurrencyCode, toCurrencyCode);
        return Optional.empty();
    }
}
