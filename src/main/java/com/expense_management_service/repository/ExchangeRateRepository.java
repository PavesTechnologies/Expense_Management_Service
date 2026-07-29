package com.expense_management_service.repository;

import com.expense_management_service.entity.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    /** Exact currency-pair + effective-date match — used for duplicate detection and point lookups. */
    Optional<ExchangeRate> findByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
            UUID fromCurrencyId, UUID toCurrencyId, LocalDate effectiveDate);

    /** Cheap existence check for duplicate-rate validation. */
    boolean existsByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDate(
            UUID fromCurrencyId, UUID toCurrencyId, LocalDate effectiveDate);

    /**
     * Historical "rate effective as of a date" lookup (BR-05): the most recent rate on or before
     * the given date. Ordered by {@code effectiveDate} first, then by {@code fetchedAt} as a
     * tiebreak — if two rows share the same effective date (e.g. a manual correction recorded the
     * same day as a scheduled refresh), the one fetched most recently wins, so this never returns
     * an arbitrary/outdated row when effective dates tie.
     */
    Optional<ExchangeRate> findFirstByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescFetchedAtDesc(
            UUID fromCurrencyId, UUID toCurrencyId, LocalDate asOfDate);

    /** Latest known rate for a pair, regardless of date. */
    Optional<ExchangeRate> findFirstByFromCurrency_CurrencyIdAndToCurrency_CurrencyIdOrderByEffectiveDateDesc(
            UUID fromCurrencyId, UUID toCurrencyId);
}
