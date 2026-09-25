package com.expense_management_service.entity;

import com.expense_management_service.enums.BudgetEncumbranceStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A reservation against one {@code CostCenterBudget}, created at submission (or restart) time and
 * resolved into either {@code CONSUMED} (AP payment) or {@code RELEASED} (correction, restart,
 * recall, cancellation, or rejection). Never mutates {@code CostCenterBudget.availableBudget} itself
 * - Effective Available Budget is computed as {@code availableBudget - SUM(ACTIVE encumbrances)},
 * entirely at read time; {@code availableBudget} keeps its existing, unmodified meaning ("not yet
 * actually consumed"), touched only by the existing, unmodified {@code consumeBudget()}.
 * <p>
 * {@code budget} is set once at creation and is IMMUTABLE - never re-pointed to a different fiscal
 * year's {@code CostCenterBudget}, even across a year boundary. A report submitted in FY2026 and paid
 * in FY2027 still consumes against the FY2026 budget this row was created against. It is nullable
 * specifically to represent the unbudgeted case ({@code unbudgeted == true}), where no
 * {@code CostCenterBudget} row exists at all for the target cost center/fiscal year to point to.
 * <p>
 * {@code split} is nullable: null represents the single report-level reservation for a normal
 * (non-split) expense's unsplit remainder, sized and targeted exactly like the existing
 * {@code consumeBudget(report.getCostCenter(), report.getFiscalYear(), report.getTotalAmount())}
 * call already is; a populated value represents one specific {@code ExpenseSplit}'s reservation. A
 * database unique constraint prevents duplicate split-level rows within one report/cycle; "at most
 * one null-split (report-level) row per report/cycle" is a service-layer invariant (Phase 3), not a
 * database constraint, since SQL unique indexes do not treat multiple NULLs as a single value.
 */
@Entity
@Table(name = "budget_encumbrance", uniqueConstraints = {
        @UniqueConstraint(name = "uk_budget_encumbrance_report_cycle_split", columnNames = {"report_id", "submission_cycle", "split_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class BudgetEncumbrance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "encumbrance_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID encumbranceId;

    /** Immutable once set - see class javadoc. Null only when {@code unbudgeted == true}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id")
    @ToString.Exclude
    private CostCenterBudget budget;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    /** Null = the report-level reservation for a normal expense's unsplit remainder; populated = one specific ExpenseSplit's reservation. Mutually exclusive with a null value meaning "normal". */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "split_id")
    @ToString.Exclude
    private ExpenseSplit split;

    /** Scopes this row to one submission cycle - mirrors {@code ApprovalLevelInstance.submissionCycle} exactly, same reason: distinguishes the current cycle's rows from a stale prior cycle's after a restart. */
    @Column(name = "submission_cycle", nullable = false)
    private Integer submissionCycle;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255, nullable = false)
    private BudgetEncumbranceStatus status;

    /** True when no CostCenterBudget row existed at all for the target cost center/fiscal year and CostCenter.allowUnbudgeted permitted the submission to proceed anyway. */
    @Column(name = "unbudgeted", nullable = false)
    @Builder.Default
    private Boolean unbudgeted = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /** Optimistic lock - protects concurrent release/consume attempts on the same row. */
    @Version
    @Column(name = "version")
    private Long version;
}
