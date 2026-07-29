package com.expense_management_service.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateRefreshResponse;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExchangeRate;
import com.expense_management_service.mapper.ExchangeRateMapper;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExchangeRateRepository;
import com.expense_management_service.service.ExchangeRateProvider;
import com.expense_management_service.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ExchangeRateServiceImpl implements ExchangeRateService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String REFRESH_SOURCE = "SCHEDULED_REFRESH";

    private final ExchangeRateRepository exchangeRateRepository;
    private final CurrencyRepository currencyRepository;
    private final ExchangeRateMapper exchangeRateMapper;
    private final ExchangeRateProvider exchangeRateProvider;

    @Value("${exchange.rate.base-currency}")
    private String baseCurrencyCode;

    @Override
    public ExchangeRateResponse create(ExchangeRateRequest request) {
        validateDifferentCurrencies(request);
        assertPositiveRate(request.rate());

        Currency fromCurrency = findCurrency(request.fromCurrencyId());
        Currency toCurrency = findCurrency(request.toCurrencyId());
        assertCurrencyActive(fromCurrency);
        assertCurrencyActive(toCurrency);
        assertNoDuplicateRate(null, request.fromCurrencyId(), request.toCurrencyId(), request.effectiveDate());

        ExchangeRate entity = exchangeRateMapper.toEntity(request);
        entity.setFromCurrency(fromCurrency);
        entity.setToCurrency(toCurrency);
        entity.setFetchedAt(LocalDateTime.now());

        ExchangeRate saved = exchangeRateRepository.save(entity);
        log.info("Created exchange rate {} -> {} = {} effective {}",
                fromCurrency.getCurrencyCode(), toCurrency.getCurrencyCode(), request.rate(), request.effectiveDate());
        return exchangeRateMapper.toResponse(saved);
    }

    @Override
    public ExchangeRateResponse update(UUID exchangeRateId, ExchangeRateRequest request) {
        validateDifferentCurrencies(request);
        assertPositiveRate(request.rate());

        ExchangeRate entity = findEntity(exchangeRateId);
        Currency fromCurrency = findCurrency(request.fromCurrencyId());
        Currency toCurrency = findCurrency(request.toCurrencyId());
        assertCurrencyActive(fromCurrency);
        assertCurrencyActive(toCurrency);
        assertNoDuplicateRate(exchangeRateId, request.fromCurrencyId(), request.toCurrencyId(), request.effectiveDate());

        exchangeRateMapper.updateEntity(entity, request);
        entity.setFromCurrency(fromCurrency);
        entity.setToCurrency(toCurrency);

        ExchangeRate saved = exchangeRateRepository.save(entity);
        log.info("Updated exchange rate {} ({} -> {} = {} effective {})",
                exchangeRateId, fromCurrency.getCurrencyCode(), toCurrency.getCurrencyCode(),
                request.rate(), request.effectiveDate());
        return exchangeRateMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponse getById(UUID exchangeRateId) {
        return exchangeRateMapper.toResponse(findEntity(exchangeRateId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> getAll() {
        return exchangeRateRepository.findAll().stream().map(exchangeRateMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> getFiltered(LocalDate effectiveDate, UUID fromCurrencyId, UUID toCurrencyId) {
        return exchangeRateRepository.findAll().stream()
                .filter(rate -> effectiveDate == null || effectiveDate.equals(rate.getEffectiveDate()))
                .filter(rate -> fromCurrencyId == null
                        || (rate.getFromCurrency() != null && fromCurrencyId.equals(rate.getFromCurrency().getCurrencyId())))
                .filter(rate -> toCurrencyId == null
                        || (rate.getToCurrency() != null && toCurrencyId.equals(rate.getToCurrency().getCurrencyId())))
                .map(exchangeRateMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExchangeRateResponse getHistoricalRate(UUID fromCurrencyId, UUID toCurrencyId, LocalDate asOfDate) {
        ExchangeRate rate = exchangeRateRepository
                .findFirstByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescFetchedAtDesc(
                        fromCurrencyId, toCurrencyId, asOfDate)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No exchange rate is available for this currency pair effective on or before " + asOfDate));
        return exchangeRateMapper.toResponse(rate);
    }

    @Override
    public ExchangeRateRefreshResponse refreshRates() {
        List<Currency> activeCurrencies = currencyRepository.findAll().stream()
                .filter(currency -> STATUS_ACTIVE.equalsIgnoreCase(currency.getStatus()))
                .toList();

        Optional<Currency> baseCurrency = activeCurrencies.stream()
                .filter(currency -> baseCurrencyCode.equalsIgnoreCase(currency.getCurrencyCode()))
                .findFirst();

        if (baseCurrency.isEmpty()) {
            log.warn("Configured base currency '{}' is not an Active currency in the Currency master table; skipping refresh",
                    baseCurrencyCode);
            return new ExchangeRateRefreshResponse(0, 0, 0, LocalDateTime.now(),
                    "Configured base currency '" + baseCurrencyCode
                            + "' is not an Active currency in the Currency master table; refresh skipped.");
        }

        Currency base = baseCurrency.get();
        List<CurrencyPair> pairs = activeCurrencies.stream()
                .filter(currency -> !currency.getCurrencyId().equals(base.getCurrencyId()))
                .flatMap(currency -> Stream.of(new CurrencyPair(base, currency), new CurrencyPair(currency, base)))
                .toList();

        LocalDate today = LocalDate.now();
        int processed = 0;
        int created = 0;
        int skipped = 0;

        for (CurrencyPair pair : pairs) {
            processed++;
            Currency fromCurrency = pair.from();
            Currency toCurrency = pair.to();

            Optional<BigDecimal> fetched = exchangeRateProvider.fetchRate(fromCurrency.getCurrencyCode(), toCurrency.getCurrencyCode());
            if (fetched.isEmpty()) {
                skipped++;
                continue;
            }
            if (exchangeRateRepository.existsByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                    fromCurrency.getCurrencyId(), toCurrency.getCurrencyId(), today)) {
                log.debug("Rate for {} -> {} already recorded for {}, skipping", fromCurrency.getCurrencyCode(),
                        toCurrency.getCurrencyCode(), today);
                skipped++;
                continue;
            }

            assertPositiveRate(fetched.get());
            ExchangeRate newRate = ExchangeRate.builder()
                    .fromCurrency(fromCurrency)
                    .toCurrency(toCurrency)
                    .rate(fetched.get())
                    .effectiveDate(today)
                    .source(REFRESH_SOURCE)
                    .fetchedAt(LocalDateTime.now())
                    .build();
            exchangeRateRepository.save(newRate);
            created++;
        }

        String note = created == 0 && processed > 0
                ? "No live exchange-rate provider is configured; the refresh scanned " + processed
                        + " active currency pair(s) but fetched no rates. See ExchangeRateProvider."
                : "Refresh completed: " + created + " new rate(s) recorded out of " + processed + " pair(s) scanned.";

        log.info("Exchange rate refresh finished: processed={}, created={}, skipped={}", processed, created, skipped);
        return new ExchangeRateRefreshResponse(processed, created, skipped, LocalDateTime.now(), note);
    }

    private record CurrencyPair(Currency from, Currency to) {
    }

    @Override
    public void delete(UUID exchangeRateId) {
        exchangeRateRepository.delete(findEntity(exchangeRateId));
    }

    private void validateDifferentCurrencies(ExchangeRateRequest request) {
        if (request.fromCurrencyId().equals(request.toCurrencyId())) {
            throw new IllegalArgumentException("fromCurrencyId and toCurrencyId must be different");
        }
    }

    private void assertPositiveRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Exchange rate must be a positive, non-zero value");
        }
    }

    private void assertCurrencyActive(Currency currency) {
        if (!STATUS_ACTIVE.equalsIgnoreCase(currency.getStatus())) {
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode() + " is not Active and cannot be used in an exchange rate");
        }
    }

    private void assertNoDuplicateRate(UUID currentExchangeRateId, UUID fromCurrencyId, UUID toCurrencyId, LocalDate effectiveDate) {
        exchangeRateRepository
                .findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(fromCurrencyId, toCurrencyId, effectiveDate)
                .ifPresent(existing -> {
                    if (!existing.getExchangeRateId().equals(currentExchangeRateId)) {
                        throw new DuplicateResourceException(
                                "An exchange rate for this currency pair already exists for effective date " + effectiveDate);
                    }
                });
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }

    private ExchangeRate findEntity(UUID exchangeRateId) {
        return exchangeRateRepository.findById(exchangeRateId)
                .orElseThrow(() -> new ResourceNotFoundException("ExchangeRate not found with id: " + exchangeRateId));
    }
}
