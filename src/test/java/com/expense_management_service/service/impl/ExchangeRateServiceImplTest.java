package com.expense_management_service.service.impl;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceImplTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private ExchangeRateProvider exchangeRateProvider;

    private ExchangeRateServiceImpl exchangeRateService;

    private Currency usd;
    private Currency inr;

    @BeforeEach
    void setUp() {
        exchangeRateService = new ExchangeRateServiceImpl(
                exchangeRateRepository, currencyRepository, new ExchangeRateMapper(), exchangeRateProvider);
        ReflectionTestUtils.setField(exchangeRateService, "baseCurrencyCode", "INR");

        usd = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("USD")
                .currencyName("US Dollar").decimalPlaces(2).status("ACTIVE").build();
        inr = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("INR")
                .currencyName("Indian Rupee").decimalPlaces(2).status("ACTIVE").build();
    }

    @Test
    void create_savesNewRate_whenValidAndUnique() {
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL");

        when(currencyRepository.findById(usd.getCurrencyId())).thenReturn(Optional.of(usd));
        when(currencyRepository.findById(inr.getCurrencyId())).thenReturn(Optional.of(inr));
        when(exchangeRateRepository.findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                usd.getCurrencyId(), inr.getCurrencyId(), request.effectiveDate())).thenReturn(Optional.empty());
        when(exchangeRateRepository.save(any(ExchangeRate.class))).thenAnswer(invocation -> {
            ExchangeRate saved = invocation.getArgument(0);
            saved.setExchangeRateId(UUID.randomUUID());
            return saved;
        });

        ExchangeRateResponse response = exchangeRateService.create(request);

        assertThat(response.fromCurrencyCode()).isEqualTo("USD");
        assertThat(response.toCurrencyCode()).isEqualTo("INR");
        assertThat(response.rate()).isEqualByComparingTo("83.25");
    }

    @Test
    void create_throwsDuplicateResourceException_whenRateAlreadyExistsForPairAndDate() {
        LocalDate effectiveDate = LocalDate.now();
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.valueOf(83.25), effectiveDate, "MANUAL");
        ExchangeRate existing = ExchangeRate.builder().exchangeRateId(UUID.randomUUID()).build();

        when(currencyRepository.findById(usd.getCurrencyId())).thenReturn(Optional.of(usd));
        when(currencyRepository.findById(inr.getCurrencyId())).thenReturn(Optional.of(inr));
        when(exchangeRateRepository.findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                usd.getCurrencyId(), inr.getCurrencyId(), effectiveDate)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> exchangeRateService.create(request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenRateIsZeroOrNegative() {
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.ZERO, LocalDate.now(), "MANUAL");

        assertThatThrownBy(() -> exchangeRateService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenFromCurrencyIsNotActive() {
        Currency inactiveUsd = Currency.builder().currencyId(usd.getCurrencyId()).currencyCode("USD").status("INACTIVE").build();
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.valueOf(83.25), LocalDate.now(), "MANUAL");

        when(currencyRepository.findById(usd.getCurrencyId())).thenReturn(Optional.of(inactiveUsd));
        when(currencyRepository.findById(inr.getCurrencyId())).thenReturn(Optional.of(inr));

        assertThatThrownBy(() -> exchangeRateService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenFromAndToCurrenciesAreTheSame() {
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), usd.getCurrencyId(), BigDecimal.valueOf(1), LocalDate.now(), "MANUAL");

        assertThatThrownBy(() -> exchangeRateService.create(request))
                .isInstanceOf(IllegalArgumentException.class);

        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void update_allowsRateToKeepItsOwnEffectiveDate() {
        UUID id = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now();
        ExchangeRate existing = ExchangeRate.builder()
                .exchangeRateId(id).fromCurrency(usd).toCurrency(inr)
                .rate(BigDecimal.valueOf(80)).effectiveDate(effectiveDate).build();
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.valueOf(84), effectiveDate, "MANUAL");

        when(exchangeRateRepository.findById(id)).thenReturn(Optional.of(existing));
        when(currencyRepository.findById(usd.getCurrencyId())).thenReturn(Optional.of(usd));
        when(currencyRepository.findById(inr.getCurrencyId())).thenReturn(Optional.of(inr));
        when(exchangeRateRepository.findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                usd.getCurrencyId(), inr.getCurrencyId(), effectiveDate)).thenReturn(Optional.of(existing));
        when(exchangeRateRepository.save(any(ExchangeRate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExchangeRateResponse response = exchangeRateService.update(id, request);

        assertThat(response.rate()).isEqualByComparingTo("84");
    }

    @Test
    void update_throwsDuplicateResourceException_whenEffectiveDateBelongsToAnotherRate() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now();
        ExchangeRate existing = ExchangeRate.builder()
                .exchangeRateId(id).fromCurrency(usd).toCurrency(inr)
                .rate(BigDecimal.valueOf(80)).effectiveDate(effectiveDate.minusDays(1)).build();
        ExchangeRate other = ExchangeRate.builder().exchangeRateId(otherId).build();
        ExchangeRateRequest request = new ExchangeRateRequest(
                usd.getCurrencyId(), inr.getCurrencyId(), BigDecimal.valueOf(84), effectiveDate, "MANUAL");

        when(exchangeRateRepository.findById(id)).thenReturn(Optional.of(existing));
        when(currencyRepository.findById(usd.getCurrencyId())).thenReturn(Optional.of(usd));
        when(currencyRepository.findById(inr.getCurrencyId())).thenReturn(Optional.of(inr));
        when(exchangeRateRepository.findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                usd.getCurrencyId(), inr.getCurrencyId(), effectiveDate)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> exchangeRateService.update(id, request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID id = UUID.randomUUID();
        when(exchangeRateRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getFiltered_appliesOnlyTheProvidedFilters() {
        ExchangeRate matching = ExchangeRate.builder()
                .exchangeRateId(UUID.randomUUID()).fromCurrency(usd).toCurrency(inr)
                .rate(BigDecimal.TEN).effectiveDate(LocalDate.of(2026, 1, 1)).build();
        ExchangeRate nonMatchingDate = ExchangeRate.builder()
                .exchangeRateId(UUID.randomUUID()).fromCurrency(usd).toCurrency(inr)
                .rate(BigDecimal.TEN).effectiveDate(LocalDate.of(2026, 2, 1)).build();

        when(exchangeRateRepository.findAll()).thenReturn(List.of(matching, nonMatchingDate));

        List<ExchangeRateResponse> result = exchangeRateService.getFiltered(
                LocalDate.of(2026, 1, 1), usd.getCurrencyId(), inr.getCurrencyId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).exchangeRateId()).isEqualTo(matching.getExchangeRateId());
    }

    @Test
    void getHistoricalRate_returnsMostRecentRateOnOrBeforeGivenDate() {
        LocalDate asOfDate = LocalDate.of(2026, 3, 15);
        ExchangeRate historical = ExchangeRate.builder()
                .exchangeRateId(UUID.randomUUID()).fromCurrency(usd).toCurrency(inr)
                .rate(BigDecimal.valueOf(82)).effectiveDate(LocalDate.of(2026, 3, 1)).build();

        when(exchangeRateRepository
                .findFirstByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        usd.getCurrencyId(), inr.getCurrencyId(), asOfDate))
                .thenReturn(Optional.of(historical));

        ExchangeRateResponse response = exchangeRateService.getHistoricalRate(usd.getCurrencyId(), inr.getCurrencyId(), asOfDate);

        assertThat(response.rate()).isEqualByComparingTo("82");
    }

    @Test
    void getHistoricalRate_throwsResourceNotFoundException_whenNoRateExistsOnOrBeforeDate() {
        LocalDate asOfDate = LocalDate.of(2026, 3, 15);
        when(exchangeRateRepository
                .findFirstByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDesc(
                        usd.getCurrencyId(), inr.getCurrencyId(), asOfDate))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.getHistoricalRate(usd.getCurrencyId(), inr.getCurrencyId(), asOfDate))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refreshRates_derivesPairsFromActiveCurrencyMasterData_notFromExistingExchangeRateRows() {
        // USD has never had an exchange_rate row created for it — the refresh must still pick it up
        // purely because it is Active in the Currency table.
        when(currencyRepository.findAll()).thenReturn(List.of(inr, usd));
        when(exchangeRateProvider.fetchRate(anyString(), anyString())).thenReturn(Optional.empty());

        ExchangeRateRefreshResponse response = exchangeRateService.refreshRates();

        // 1 non-base Active currency (USD) x 2 directions (INR->USD, USD->INR) = 2 pairs processed.
        assertThat(response.pairsProcessed()).isEqualTo(2);
        verify(exchangeRateProvider).fetchRate("INR", "USD");
        verify(exchangeRateProvider).fetchRate("USD", "INR");
    }

    @Test
    void refreshRates_skipsPair_whenProviderHasNoRateAvailable() {
        when(currencyRepository.findAll()).thenReturn(List.of(inr, usd));
        when(exchangeRateProvider.fetchRate(anyString(), anyString())).thenReturn(Optional.empty());

        ExchangeRateRefreshResponse response = exchangeRateService.refreshRates();

        assertThat(response.pairsProcessed()).isEqualTo(2);
        assertThat(response.ratesCreated()).isZero();
        assertThat(response.ratesSkipped()).isEqualTo(2);
        assertThat(response.note()).containsIgnoringCase("no live exchange-rate provider");
        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void refreshRates_createsNewRates_whenProviderReturnsRatesAndNoneExistYetForToday() {
        when(currencyRepository.findAll()).thenReturn(List.of(inr, usd));
        when(exchangeRateProvider.fetchRate("INR", "USD")).thenReturn(Optional.of(BigDecimal.valueOf(0.012)));
        when(exchangeRateProvider.fetchRate("USD", "INR")).thenReturn(Optional.of(BigDecimal.valueOf(83.5)));
        when(exchangeRateRepository.existsByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                any(), any(), eq(LocalDate.now()))).thenReturn(false);
        when(exchangeRateRepository.save(any(ExchangeRate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExchangeRateRefreshResponse response = exchangeRateService.refreshRates();

        assertThat(response.pairsProcessed()).isEqualTo(2);
        assertThat(response.ratesCreated()).isEqualTo(2);
        assertThat(response.ratesSkipped()).isZero();
        verify(exchangeRateRepository, times(2)).save(any(ExchangeRate.class));
    }

    @Test
    void refreshRates_skipsPair_whenRateAlreadyRecordedForToday() {
        when(currencyRepository.findAll()).thenReturn(List.of(inr, usd));
        when(exchangeRateProvider.fetchRate(anyString(), anyString())).thenReturn(Optional.of(BigDecimal.valueOf(83.5)));
        when(exchangeRateRepository.existsByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
                any(), any(), eq(LocalDate.now()))).thenReturn(true);

        ExchangeRateRefreshResponse response = exchangeRateService.refreshRates();

        assertThat(response.ratesCreated()).isZero();
        assertThat(response.ratesSkipped()).isEqualTo(2);
        verify(exchangeRateRepository, never()).save(any());
    }

    @Test
    void refreshRates_skipsEntirely_whenConfiguredBaseCurrencyIsNotAnActiveCurrency() {
        Currency inactiveInr = Currency.builder().currencyId(inr.getCurrencyId()).currencyCode("INR").status("INACTIVE").build();
        when(currencyRepository.findAll()).thenReturn(List.of(inactiveInr, usd));

        ExchangeRateRefreshResponse response = exchangeRateService.refreshRates();

        assertThat(response.pairsProcessed()).isZero();
        assertThat(response.ratesCreated()).isZero();
        assertThat(response.note()).containsIgnoringCase("not an Active currency");
        verify(exchangeRateProvider, never()).fetchRate(any(), any());
    }

    @Test
    void delete_removesExistingRate() {
        UUID id = UUID.randomUUID();
        ExchangeRate existing = ExchangeRate.builder().exchangeRateId(id).build();
        when(exchangeRateRepository.findById(id)).thenReturn(Optional.of(existing));

        exchangeRateService.delete(id);

        verify(exchangeRateRepository).delete(existing);
    }
}
