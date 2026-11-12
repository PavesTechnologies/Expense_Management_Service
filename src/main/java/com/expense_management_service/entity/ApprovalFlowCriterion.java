package com.expense_management_service.entity;

import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

/**
 * One condition within an {@link ApprovalFlow}'s criteria expression, referenced by {@code index}
 * from the flow's {@code criteriaPattern} (e.g. index {@code 1} in {@code "(1 AND 2) OR 3"}).
 */
@Entity
@Table(name = "approval_flow_criterion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalFlowCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "criterion_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID criterionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    @ToString.Exclude
    private ApprovalFlow flow;

    /** The number referenced by the parent flow's {@code criteriaPattern}. */
    @Column(name = "criterion_index", nullable = false)
    private Integer index;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", length = 255, nullable = false)
    private CriterionField field;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", length = 255, nullable = false)
    private CriterionOperator operator;

    /**
     * Free text, parsed defensively per {@code field}'s expected type (mirrors
     * {@code PolicyRule.ruleValue}) - a decimal amount for {@code AMOUNT}, a category/department/
     * cost-center identifier otherwise.
     */
    @Column(name = "value", length = 255)
    private String value;
}
