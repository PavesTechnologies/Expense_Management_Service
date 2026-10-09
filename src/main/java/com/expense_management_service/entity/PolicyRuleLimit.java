package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A currency-specific amount limit for one {@link PolicyRule} (e.g. India -&gt; ₹1,500/day, USA -&gt;
 * $60/day, both under the same Meals rule). A rule with zero {@code PolicyRuleLimit} rows is in
 * "flat limit" mode and keeps using {@code PolicyRule#getRuleValue()} exactly as it always has -
 * adding rows here is what opts a rule into per-currency mode; see {@code
 * DefaultPolicyEvaluator#checkAmountLimit} for the precedence.
 */
@Entity
@Table(name = "policy_rule_limit", uniqueConstraints = @UniqueConstraint(name = "uk_policy_rule_limit_currency", columnNames = {"policy_rule_id", "currency_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyRuleLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "limit_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID limitId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_rule_id", nullable = false)
    @ToString.Exclude
    private PolicyRule policyRule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    @ToString.Exclude
    private Currency currency;

    @Column(name = "limit_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal limitAmount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
