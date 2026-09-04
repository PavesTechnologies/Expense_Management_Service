package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A recorded, advisory-only compliance flag against a single {@link ExpenseLineItem}. EP05 never
 * blocks on these — a violation is informational, surfaced to the employee (who may attach a
 * {@link #justification}) and the approver, and never gates a save, a submission, or an approval
 * decision.
 * <p>
 * {@code ruleType}/{@code severity}/{@code message} are denormalised off {@link #policyRule} at the
 * moment of detection, so a previously-recorded violation still renders correctly even after the
 * rule that produced it is edited or deleted — hence {@link #policyRule} is nullable.
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
