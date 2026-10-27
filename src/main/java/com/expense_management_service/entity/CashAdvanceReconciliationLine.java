package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One advance's check within a reconciliation run. {@code discrepancy} is stored minus expected at the period end.
 * {@code flagged} is true only when the difference is non-zero and no later movement explains it. A flagged line needs a
 * resolution note before the run can be approved. Resolving a line records a note; it never changes a balance.
 */
@Entity
@Table(name = "cash_advance_reconciliation_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "run")
public class CashAdvanceReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reconciliation_line_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID reconciliationLineId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_run_id", nullable = false)
    private CashAdvanceReconciliationRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false)
    private CashAdvance advance;

    @Column(name = "employee_id", length = 255)
    private String employeeId;

    @Column(name = "expected_closing", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedClosing;

    @Column(name = "stored_closing", nullable = false, precision = 19, scale = 4)
    private BigDecimal storedClosing;

    @Column(name = "discrepancy", nullable = false, precision = 19, scale = 4)
    private BigDecimal discrepancy;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    /** Movements dated after the period end. A non-zero count explains a difference without flagging it. */
    @Column(name = "later_movements", nullable = false)
    private int laterMovements;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
