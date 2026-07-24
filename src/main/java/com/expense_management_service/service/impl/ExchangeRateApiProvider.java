package com.expense_management_service.service.impl;

import com.expense_management_service.dto.external.ExchangeRateApiResponse;
import com.expense_management_service.service.ExchangeRateProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Live {@link ExchangeRateProvider} backed by the configured Exchange Rate API
 * ({@code exchange.rate.api.url} / {@code exchange.rate.api.key} / {@code exchange.rate.base-currency}).
 * <p>
 * The API's {@code latest} endpoint returns every conversion rate relative to a single base
 * currency in one call. Rather than issuing one HTTP call per requested pair (costly against a
 * rate-limited API key), each successful response is cached briefly and pairs not directly
 * involving the base currency are triangulated from it:
 * {@code rate(from -> to) = rate(base -> to) / rate(base -> from)}.
 * <p>
 * Marked {@link Primary} so it is selected over {@link StubExchangeRateProvider} wherever
 * {@link ExchangeRateProvider} is injected, without requiring any change to the service layer.
 */
@Component
@Primary
@Slf4j
public class ExchangeRateApiProvider implements ExchangeRateProvider {

    private static final int RATE_SCALE = 6;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String apiKey;
    private final String baseCurrency;

    private volatile Snapshot snapshot;

    public ExchangeRateApiProvider(
            @Value("${exchange.rate.api.url}") String apiUrl,
            @Value("${exchange.rate.api.key}") String apiKey,
            @Value("${exchange.rate.base-currency}") String baseCurrency) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(requestFactory)
                .build();
        this.apiKey = apiKey;
        this.baseCurrency = baseCurrency.toUpperCase();
    }

    @Override
    public Optional<BigDecimal> fetchRate(String fromCurrencyCode, String toCurrencyCode) {
        String from = fromCurrencyCode.toUpperCase();
        String to = toCurrencyCode.toUpperCase();

        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE.setScale(RATE_SCALE, RoundingMode.HALF_UP));
        }

        Optional<Map<String, BigDecimal>> conversionRates = latestConversionRates();
        if (conversionRates.isEmpty()) {
            return Optional.empty();
        }

        Optional<BigDecimal> rate = computeRate(from, to, conversionRates.get());
        if (rate.isEmpty()) {
            log.warn("Live exchange-rate provider has no rate available for {} -> {} (base={})", from, to, baseCurrency);
        }
        return rate;
    }

    /** Returns the cached base-currency conversion table, fetching a fresh one from the API if the cache is stale/absent. */
    private synchronized Optional<Map<String, BigDecimal>> latestConversionRates() {
        Snapshot current = this.snapshot;
        if (current != null && Duration.between(current.fetchedAt(), Instant.now()).compareTo(SNAPSHOT_TTL) < 0) {
            return Optional.of(current.conversionRates());
        }

        Instant start = Instant.now();
        log.info("Requesting live exchange rate table: base={}", baseCurrency);

        try {
            ExchangeRateApiResponse response = restClient.get()
                    .uri("/{apiKey}/latest/{base}", apiKey, baseCurrency)
                    .retrieve()
                    .body(ExchangeRateApiResponse.class);

            long elapsedMs = Duration.between(start, Instant.now()).toMillis();

            if (response == null) {
                log.error("Exchange rate API returned an empty response body (elapsed={}ms)", elapsedMs);
                return Optional.empty();
            }
            if (!"success".equalsIgnoreCase(response.result())) {
                log.error("Exchange rate API returned error response: errorType={}, elapsed={}ms", response.errorType(), elapsedMs);
                return Optional.empty();
            }
            if (response.conversionRates() == null || response.conversionRates().isEmpty()) {
                log.error("Exchange rate API response contained no conversion rates (elapsed={}ms)", elapsedMs);
                return Optional.empty();
            }

            log.info("Exchange rate API responded successfully: base={}, ratesReceived={}, elapsed={}ms",
                    response.baseCode(), response.conversionRates().size(), elapsedMs);

            this.snapshot = new Snapshot(response.conversionRates(), Instant.now());
            return Optional.of(response.conversionRates());

        } catch (RestClientResponseException ex) {
            log.error("Exchange rate API call failed with HTTP status {} (base={}): {}",
                    ex.getStatusCode(), baseCurrency, ex.getMessage());
            return Optional.empty();
        } catch (RestClientException ex) {
            log.error("Exchange rate API call failed (network error or timeout, base={}): {}", baseCurrency, ex.getMessage());
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.error("Exchange rate API call failed with an unexpected/invalid response (base={}): {}", baseCurrency, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BigDecimal> computeRate(String from, String to, Map<String, BigDecimal> conversionRates) {
        if (baseCurrency.equals(from)) {
            return Optional.ofNullable(conversionRates.get(to)).map(r -> r.setScale(RATE_SCALE, RoundingMode.HALF_UP));
        }
        if (baseCurrency.equals(to)) {
            BigDecimal fromRate = conversionRates.get(from);
            if (fromRate == null || fromRate.signum() == 0) {
                return Optional.empty();
            }
            return Optional.of(BigDecimal.ONE.divide(fromRate, RATE_SCALE, RoundingMode.HALF_UP));
        }
        BigDecimal fromRate = conversionRates.get(from);
        BigDecimal toRate = conversionRates.get(to);
        if (fromRate == null || toRate == null || fromRate.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(toRate.divide(fromRate, RATE_SCALE, RoundingMode.HALF_UP));
    }

    private record Snapshot(Map<String, BigDecimal> conversionRates, Instant fetchedAt) {
    }
}
