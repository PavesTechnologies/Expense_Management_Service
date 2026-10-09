package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cost_center_budget", uniqueConstraints = @UniqueConstraint(columnNames = {"cost_center_id", "fiscal_year"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CostCenterBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "budget_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID budgetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id", nullable = false)
    @ToString.Exclude
    private CostCenter costCenter;

    @Column(name = "fiscal_year", length = 255, nullable = false)
    private String fiscalYear;

    @Column(name = "budget_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal budgetAmount;

    @Column(name = "available_budget", precision = 19, scale = 4, nullable = false)
    private BigDecimal availableBudget;

    /**
     * One-time, admin-entered figure recorded when this fiscal year's row is created - never
     * recalculated afterward. Folded into {@code availableBudget}'s initial value at creation
     * ({@code availableBudget = budgetAmount + rolloverFromPrevious}); {@code consumeBudget()}
     * itself is never touched by this field. Null means no rollover was ever entered for this year.
     */
    @Column(name = "rollover_from_previous", precision = 19, scale = 4)
    private BigDecimal rolloverFromPrevious;

    /**
     * Governance/audit flag only - does NOT trigger any automatic rollover process (none exists).
     * Records whether this cost center is eligible to have {@code rolloverFromPrevious} entered on
     * a following year's row.
     */
    @Column(name = "allow_rollover", nullable = false)
    @Builder.Default
    private Boolean allowRollover = false;

    /**
     * Fixed absolute ceiling (not a percentage) on how much of a prior year's true unencumbered
     * remainder this year's row may accept as {@code rolloverFromPrevious}. Null means no cap.
     */
    @Column(name = "rollover_cap", precision = 19, scale = 4)
    private BigDecimal rolloverCap;

    /**
     * Fixed absolute amount (not a percentage). When post-encumbrance Effective Available Budget
     * drops below this value while still remaining &gt;= 0, the Cost Center Owner is warned at their
     * existing approval screen - this never blocks a submission and is unrelated to the hard
     * insufficient-budget failure at &lt; 0. Null means no warning threshold configured.
     */
    @Column(name = "warning_threshold", precision = 19, scale = 4)
    private BigDecimal warningThreshold;

    /**
     * Optimistic lock - protects against two concurrent Finance approvals against the same cost
     * center racing on a read-modify-write of {@link #availableBudget} (a classic lost-update
     * bug for a financial ledger value). Same pattern as {@code ExpenseReport.version}/{@code
     * ApprovalLineItemReview.version} - added for AP/budget-consumption (see {@code
     * CostCenterBudgetServiceImpl.consumeBudget}), the first writer to this row after creation.
     */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
