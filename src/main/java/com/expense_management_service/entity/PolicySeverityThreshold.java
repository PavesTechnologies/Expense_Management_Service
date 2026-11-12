package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyOverageTier;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An admin-editable percent-over-limit band mapping to a {@link PolicyOverageTier}. {@code policy}
 * is nullable: a null-policy row is a global default band, consulted only when the specific policy
 * has no bands of its own. {@code maxPercentOver} is nullable too, for the open-ended top tier
 * (e.g. SEVERE = 60% and up). See {@code DefaultPolicyEvaluator#resolveSeverityTier} for how a set
 * of these is matched against a computed overage percentage, and its own built-in fallback bands
 * for when neither a policy-specific nor a global set exists at all.
 */
@Entity
@Table(name = "policy_severity_threshold")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicySeverityThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "threshold_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID thresholdId;

    /** Null = a global default band, used only when the resolved policy has no bands of its own. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    @ToString.Exclude
    private Policy policy;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 255, nullable = false)
    private PolicyOverageTier tier;

    @Column(name = "min_percent_over", precision = 7, scale = 2, nullable = false)
    private BigDecimal minPercentOver;

    /** Null = open-ended (no upper bound) - only valid for the highest tier in a given band set. */
    @Column(name = "max_percent_over", precision = 7, scale = 2)
    private BigDecimal maxPercentOver;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
