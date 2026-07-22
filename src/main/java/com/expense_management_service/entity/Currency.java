package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "currency", uniqueConstraints = @UniqueConstraint(columnNames = "currency_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Currency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "currency_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID currencyId;

    @Column(name = "currency_code", length = 255, nullable = false)
    private String currencyCode;

    @Column(name = "currency_name", length = 255, nullable = false)
    private String currencyName;

    @Column(name = "symbol", length = 255)
    private String symbol;

    @Column(name = "decimal_places", nullable = false)
    private Integer decimalPlaces;

    @Column(name = "status", length = 255)
    private String status;

    @OneToMany(mappedBy = "fromCurrency")
    @Builder.Default
    @ToString.Exclude
    private List<ExchangeRate> outgoingExchangeRates = new ArrayList<>();

    @OneToMany(mappedBy = "toCurrency")
    @Builder.Default
    @ToString.Exclude
    private List<ExchangeRate> incomingExchangeRates = new ArrayList<>();

    @OneToMany(mappedBy = "currency")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseReport> expenseReports = new ArrayList<>();

    @OneToMany(mappedBy = "currency")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();

    @OneToMany(mappedBy = "currency")
    @Builder.Default
    @ToString.Exclude
    private List<CashAdvance> cashAdvances = new ArrayList<>();
}
