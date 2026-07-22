package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    @Column(name = "rule_type", length = 255)
    private String ruleType;

    @Column(name = "rule_value", length = 255)
    private String ruleValue;

    @Column(name = "action", length = 255)
    private String action;

    @Column(name = "severity", length = 255)
    private String severity;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "status", length = 255)
    private String status;
}
