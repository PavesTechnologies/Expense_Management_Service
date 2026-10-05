package com.expense_management_service.entity;

import com.expense_management_service.enums.TaxType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Master list of input-tax (tax paid on purchases) rates. {@code ExpenseCategory.taxCode} references
 * {@link #taxCode} by value, and its {@link #ratePercent} is what pre-fills GST on a line item.
 * Unrelated to the Accounts Receivable module's tax configuration, which covers tax charged to clients.
 */
@Entity
// Named to match V20's constraint: an unnamed one made ddl-auto=update add a duplicate index (removed in V22).
@Table(name = "tax_code", uniqueConstraints = @UniqueConstraint(name = "uk_tax_code_tax_code", columnNames = "tax_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class TaxCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tax_code_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID taxCodeId;

    @Column(name = "tax_code", length = 50, nullable = false)
    private String taxCode;

    @Column(name = "tax_name", length = 255, nullable = false)
    private String taxName;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_type", length = 32, nullable = false)
    private TaxType taxType;

    /** Combined rate in percent, e.g. 18.00 for GST 18% (CGST 9% + SGST 9%). */
    @Column(name = "rate_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal ratePercent;

    /** Whether any of this tax can be claimed back as input tax credit - derived: {@code itcRecoverablePercent > 0}. */
    @Column(name = "itc_eligible", nullable = false)
    private Boolean itcEligible;

    /** Share of the tax the company can claim back (0-100). 0 for blocked credits and EXEMPT. */
    @Column(name = "itc_recoverable_percent", precision = 5, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal itcRecoverablePercent = BigDecimal.ZERO;

    /** ISO 3166 alpha-2. Only IN is offered today; the column lets other regimes be added as configuration. */
    @Column(name = "country_code", length = 2, nullable = false)
    @Builder.Default
    private String countryCode = "IN";

    /** For regimes with state-level rates (e.g. US sales tax). Unused for India GST. */
    @Column(name = "region_code", length = 16)
    private String regionCode;

    /** The levied parts; {@link #ratePercent} is their sum. */
    @OneToMany(mappedBy = "taxCode", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    @Builder.Default
    @ToString.Exclude
    private List<TaxCodeComponent> components = new ArrayList<>();

    /** GL account the recoverable tax is posted to; optional. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "input_tax_gl_account_id")
    @ToString.Exclude
    private GlAccount inputTaxGlAccount;

    @Lob
    @Column(name = "description", columnDefinition = "LONGTEXT")
    private String description;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Active and within its effective window on {@code date}. */
    public boolean isApplicableOn(LocalDate date) {
        return "ACTIVE".equalsIgnoreCase(status)
                && !effectiveFrom.isAfter(date)
                && (effectiveTo == null || !effectiveTo.isBefore(date));
    }
}
