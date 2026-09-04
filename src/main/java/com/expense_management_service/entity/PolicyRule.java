package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "policy_rule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "policy_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID policyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    @ToString.Exclude
    private ExpenseCategory category;

    @Column(name = "policy_name", length = 255, nullable = false)
    private String policyName;

    /**
     * Nullable rather than NOT NULL: {@code V3__policy_rule_and_violation.sql} neutralises any
     * pre-existing row whose free-text value didn't match this enum by setting it to null rather
     * than deleting the row, so {@link com.expense_management_service.service.PolicyEvaluator}
     * must skip a null ruleType instead of assuming every row is well-formed.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", length = 255)
    private PolicyRuleType ruleType;

    /**
     * Meaning depends on {@link #ruleType}: a {@code BigDecimal} ceiling for {@code AMOUNT_LIMIT},
     * an integer day count for {@code BACKDATED_DAYS}, unused for every other type. Kept as free
     * text because no single column type fits all rule types; the evaluator parses defensively.
     */
    @Column(name = "rule_value", length = 255)
    private String ruleValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 255)
    private PolicySeverity severity;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "policyRule")
    @Builder.Default
    @ToString.Exclude
    private List<PolicyViolation> policyViolations = new ArrayList<>();
}
