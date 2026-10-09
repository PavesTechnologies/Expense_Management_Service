package com.expense_management_service.entity;

import com.expense_management_service.enums.TaxComponentCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One levied part of a {@link TaxCode}: CGST 9%, SGST 9%, IGST 18%, cess ... The code's
 * {@code ratePercent} is the sum of its components. Locked together with the code's rate once any
 * expense line has used the code.
 */
@Entity
@Table(name = "tax_code_component",
        uniqueConstraints = @UniqueConstraint(name = "uk_tax_code_component_code", columnNames = {"tax_code_id", "component_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class TaxCodeComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "component_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID componentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_code_id", nullable = false)
    @ToString.Exclude
    private TaxCode taxCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_code", length = 16, nullable = false)
    private TaxComponentCode componentCode;

    @Column(name = "label", length = 100, nullable = false)
    private String label;

    @Column(name = "rate_percent", precision = 5, scale = 2, nullable = false)
    private BigDecimal ratePercent;

    /** Display and calculation order; the last component absorbs rounding. */
    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    /** Optional component-specific input tax GL account; falls back to the code's. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gl_account_id")
    @ToString.Exclude
    private GlAccount glAccount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
