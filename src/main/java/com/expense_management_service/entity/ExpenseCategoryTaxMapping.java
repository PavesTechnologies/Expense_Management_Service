package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Which {@link TaxCode} applies to an {@link ExpenseCategory} on a given date. At most one mapping
 * per category covers any date. A rate change is a new code plus a new mapping row; mappings that
 * have started are never rewritten, only ended. {@code ExpenseCategory.taxCode} mirrors the
 * mapping in effect today during the transition.
 */
@Entity
@Table(name = "expense_category_tax_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseCategoryTaxMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "mapping_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID mappingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    @ToString.Exclude
    private ExpenseCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_code_id", nullable = false)
    @ToString.Exclude
    private TaxCode taxCode;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Inclusive; null = open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Covers {@code date} (both ends inclusive). */
    public boolean coversDate(LocalDate date) {
        return !effectiveFrom.isAfter(date) && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    /** True if this window and [from, to] share at least one day (null to = open-ended). */
    public boolean overlaps(LocalDate from, LocalDate to) {
        boolean startsBeforeOtherEnds = to == null || !effectiveFrom.isAfter(to);
        boolean endsAfterOtherStarts = effectiveTo == null || !effectiveTo.isBefore(from);
        return startsBeforeOtherEnds && endsAfterOtherStarts;
    }
}
