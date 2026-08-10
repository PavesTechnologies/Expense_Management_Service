package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyOverageTier;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A recorded compliance flag against a single {@link ExpenseLineItem}, surfaced to the employee
 * (who may attach a {@link #justification} when {@link #enforcementType} is {@code WARN}) and the
 * approver. A violation never gates a line-item save; whether it gates report submission depends
 * entirely on {@link #enforcementType} — see {@code ApprovalWorkflowServiceImpl.submit()}'s Block
 * gate, the only place that check happens.
 * <p>
 * {@code ruleType}/{@code severity}/{@code enforcementType}/{@code message} are denormalised off
 * {@link #policyRule} at the moment of detection, so a previously-recorded violation still renders
 * (and still enforces) correctly even after the rule that produced it is edited or deleted — hence
 * {@link #policyRule} is nullable.
 */
@Entity
@Table(name = "policy_violation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyViolation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "violation_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID violationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    /** Nullable: the rule that produced this violation may since have been edited or deleted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id")
    @ToString.Exclude
    private PolicyRule policyRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", length = 255, nullable = false)
    private PolicyRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 255, nullable = false)
    private PolicySeverity severity;

    /**
     * Denormalised from {@link #policyRule} at detection time, same reasoning as {@link
     * #severity}. {@code nullable = false} reflects the settled state after the Phase 3 backfill
     * migration (V10) - see {@code PolicyRule#enforcementType}'s identical caveat.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement_type", length = 255, nullable = false)
    private PolicyEnforcementType enforcementType;

    /**
     * The following five fields are populated only for {@code AMOUNT_LIMIT} violations - every
     * other rule type leaves them {@code null}, matching how {@code justification}/{@code
     * justifiedAt} are already conditionally populated. Together they're what makes the surfaced
     * message a delta ("over by ₹700") rather than a bare pass/fail flag.
     */
    @Column(name = "limit_value", precision = 19, scale = 4)
    private BigDecimal limitValue;

    @Column(name = "actual_value", precision = 19, scale = 4)
    private BigDecimal actualValue;

    @Column(name = "overage_percent", precision = 7, scale = 2)
    private BigDecimal overagePercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity_tier", length = 255)
    private PolicyOverageTier severityTier;

    /** The line item's own currency the limit/actual values above are expressed in - not necessarily the org's base currency. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id")
    @ToString.Exclude
    private Currency currency;

    /**
     * Which numbered {@link PolicyVersion} of {@link #policyRule}'s policy was active at detection
     * time - stamped once, here, and never rewritten by a later policy edit. This is what makes the
     * "old expenses are judged against the version active when they were submitted, not
     * retroactively" guarantee provable rather than just incidentally true.
     */
    @Column(name = "policy_version_number")
    private Integer policyVersionNumber;

    @Lob
    @Column(name = "message")
    private String message;

    @Lob
    @Column(name = "justification")
    private String justification;

    @Column(name = "justified_at")
    private LocalDateTime justifiedAt;

    @Column(name = "detected_at")
    private LocalDateTime detectedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
