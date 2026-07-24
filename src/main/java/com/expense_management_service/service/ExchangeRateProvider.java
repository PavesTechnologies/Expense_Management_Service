package com.expense_management_service.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Abstraction over an external exchange-rate source (e.g. an Exchange Rate Service API).
 * <p>
 * Implementations are swapped via Spring bean wiring — the scheduler and service layer depend
 * only on this interface, so a real HTTP-backed provider can replace {@code StubExchangeRateProvider}
 * later without touching {@link ExchangeRateService} or the refresh scheduler.
 */
public interface ExchangeRateProvider {

    /**
     * @return the latest available rate for converting one unit of {@code fromCurrencyCode} into
     * {@code toCurrencyCode}, or empty if the provider has no rate available right now.
     */
    Optional<BigDecimal> fetchRate(String fromCurrencyCode, String toCurrencyCode);
}
