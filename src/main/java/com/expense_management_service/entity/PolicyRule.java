package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyEnforcementType;
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

    /**
     * The bundle this rule belongs to. Column is deliberately named {@code policy_bundle_id}, not
     * {@code policy_id} — this table's own primary key is already called {@code policy_id} (a
     * historical artifact of the original one-rule-per-policy model, before {@link Policy} existed
     * as a separate bundle), so reusing that name for this new FK would collide within this table.
     * {@code nullable = false} reflects the settled state after the Phase 1 backfill migration
     * populated every pre-existing row and locked the column {@code NOT NULL} — this declaration
     * assumes that migration has already run against the target database (see V9's own header
     * comment for the same ddl-auto-must-run-first caveat).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_bundle_id", nullable = false)
    @ToString.Exclude
    private Policy policy;

    /**
     * Whether a violation of this rule merely warns or hard-stops submission. {@code nullable =
     * false} reflects the settled state after the Phase 3 backfill migration (see
     * V10__add_policy_enforcement_type.sql) backfills every pre-existing row to {@code WARN} and
     * locks the column {@code NOT NULL} - same ddl-auto-must-run-first caveat as {@link #policy}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_type", length = 255, nullable = false)
    private PolicyEnforcementType enforcementType;

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
