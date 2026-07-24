package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "exchange_rate", uniqueConstraints = @UniqueConstraint(
        columnNames = {"from_currency_id", "to_currency_id", "effective_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "exchange_rate_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID exchangeRateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_currency_id", nullable = false)
    @ToString.Exclude
    private Currency fromCurrency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_currency_id", nullable = false)
    @ToString.Exclude
    private Currency toCurrency;

    @Column(name = "exchange_rate", precision = 19, scale = 6, nullable = false)
    private BigDecimal rate;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "source", length = 255)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When this rate value was actually retrieved from its source (manual entry or the refresh job), as opposed to {@link #createdAt} (row insert time). */
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;
}
