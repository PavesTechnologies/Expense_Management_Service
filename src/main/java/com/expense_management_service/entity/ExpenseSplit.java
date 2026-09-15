package com.expense_management_service.entity;

import com.expense_management_service.enums.SplitType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One cost-center allocation within a split {@code ExpenseLineItem} - replaces {@code CostAllocation}
 * as the model for split allocations. {@code CostAllocation} itself is left in place, unmodified,
 * alongside this entity until frontend cutover and any live-data migration are separately confirmed
 * (see the Phase 1 compatibility analysis) - this is a purely additive introduction, not a rename.
 * <p>
 * A line item is either NORMAL (zero active rows here - uses {@code ExpenseLineItem.costCenter} /
 * {@code ExpenseReport.costCenter} exactly as today, completely unaffected by this entity's
 * existence) or SPLIT (two or more active rows here). Exactly one active row is never valid - "at
 * least 2 or exactly 0" cannot be expressed as a single-row database constraint, so this is enforced
 * at the service layer (Phase 2), not here.
 * <p>
 * Deliberately carries no approval-status field. Split-level approval decisions live entirely in
 * {@code ApprovalSplitReview}, keyed to a specific split row by FK.
 * <p>
 * <b>{@code removedAt} (production-readiness audit, post-Phase-8):</b> once a split has any {@code
 * ApprovalSplitReview} history, its row can never be physically deleted - the FK would be violated,
 * and the history must survive for audit anyway. Correcting a line item's splits therefore
 * reconciles in place: a split whose Cost Center is unchanged is UPDATED (same {@code splitId}, so
 * its existing review history stays attached and is re-evaluated for material change - see {@code
 * ApprovalWorkflowServiceImpl}'s resume-time reconciliation); a split whose Cost Center is dropped
 * from the set is soft-deleted by setting {@code removedAt} rather than being deleted outright; a
 * newly-added Cost Center becomes a brand-new row. Every query/collection read for "the split state
 * of an expense" must filter to {@code removedAt IS NULL} - a removed split's row remains only for
 * its historical reviews to still resolve their FK.
 */
@Entity
@Table(name = "expense_split", uniqueConstraints = {
        @UniqueConstraint(name = "uk_expense_split_line_item_cost_center", columnNames = {"line_item_id", "cost_center_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseSplit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "split_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID splitId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id", nullable = false)
    @ToString.Exclude
    private CostCenter costCenter;

    /** Snapshotted at creation - a split's mode never changes; correcting it means delete-and-recreate, never an in-place mode switch. */
    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", length = 255, nullable = false)
    private SplitType splitType;

    /** Populated only when {@code splitType == PERCENTAGE}; left null for FIXED_AMOUNT. */
    @Column(name = "percentage", precision = 7, scale = 2)
    private BigDecimal percentage;

    /**
     * The resolved monetary amount, always populated regardless of mode. For PERCENTAGE splits this
     * is the derived figure after rounding-remainder absorption (Rule 5) - the stored sum across a
     * line item's splits always reconciles exactly to the line item total, even though the entered
     * percentages were allowed a small tolerance before being converted.
     */
    @Column(name = "allocated_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal allocatedAmount;

    /**
     * Deterministic entry order - required so rounding-remainder absorption (Rule 5) has an
     * unambiguous "last split" to apply to, independent of database read order.
     */
    @Column(name = "split_order", nullable = false)
    private Integer splitOrder;

    /** Soft-delete marker - set instead of a physical delete once this split has approval history (see class javadoc). Null means active/current. */
    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
