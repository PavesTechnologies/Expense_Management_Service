package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.dto.response.ExchangeRateResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import java.util.UUID;

public interface ExchangeRateService {

    ExchangeRateResponse create(ExchangeRateRequest request);

    ExchangeRateResponse update(UUID exchangeRateId, ExchangeRateRequest request);

    ExchangeRateResponse getById(UUID exchangeRateId);

    List<ExchangeRateResponse> getAll();

    /** List endpoint filtering — any combination of the three parameters may be {@code null}. */
    List<ExchangeRateResponse> getFiltered(LocalDate effectiveDate, UUID fromCurrencyId, UUID toCurrencyId);

    /** BR-05: resolves the rate effective on (or most recently before) the given transaction date. */
    ExchangeRateResponse getHistoricalRate(UUID fromCurrencyId, UUID toCurrencyId, LocalDate asOfDate);

    /**
     * Converts {@code amount} from one currency to another using the historical rate effective
     * on or before {@code asOfDate}, rounded to the target currency's {@code decimalPlaces}.
     * Returns {@code amount} unchanged (no lookup) when both currencies are the same - a same-currency
     * rate row can never exist, since {@link #create} rejects fromCurrencyId == toCurrencyId.
     */
    BigDecimal convertAmount(BigDecimal amount, UUID fromCurrencyId, UUID toCurrencyId, LocalDate asOfDate);

    /** Runs the exchange-rate refresh cycle against {@link ExchangeRateProvider} for all Active currency pairs. */
    ExchangeRateRefreshResponse refreshRates();

    void delete(UUID exchangeRateId);
}
