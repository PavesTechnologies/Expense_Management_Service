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
