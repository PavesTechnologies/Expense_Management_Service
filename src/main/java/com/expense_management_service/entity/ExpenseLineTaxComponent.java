package com.expense_management_service.entity;

import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxComponentSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Snapshotted per-component tax of an {@link ExpenseLineItem} (CGST 900 + SGST 900). The amounts
 * of a line's components always sum to the line's {@code taxAmount}, and their base amounts to its
 * {@code baseTaxAmount}.
 */
@Entity
@Table(name = "expense_line_tax_component")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseLineTaxComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "line_tax_component_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID lineTaxComponentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_code", length = 16, nullable = false)
    private TaxComponentCode componentCode;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Column(name = "rate_percent", precision = 5, scale = 2)
    private BigDecimal ratePercent;

    /** In the line's own (transaction) currency. */
    @Column(name = "tax_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "base_tax_amount", precision = 19, scale = 4)
    private BigDecimal baseTaxAmount;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private TaxComponentSource source;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
