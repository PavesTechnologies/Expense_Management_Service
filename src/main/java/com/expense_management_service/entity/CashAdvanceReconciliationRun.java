package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Finance reconciliation of cash advance balances for one period. Expected balances come from the movement ledger;
 * stored balances are compared against them and differences are flagged. A run is DRAFT, then SUBMITTED, APPROVED
 * and CLOSED. A CLOSED run is immutable. No run ever overwrites a cash advance balance.
 */
@Entity
@Table(name = "cash_advance_reconciliation_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "lines")
public class CashAdvanceReconciliationRun {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reconciliation_run_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID reconciliationRunId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** MONTHLY, QUARTERLY, HALF_YEARLY or FINANCIAL_YEAR. */
    @Column(name = "period_type", nullable = false, length = 32)
    private String periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "submitted_by", length = 255)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "closed_by", length = 255)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CashAdvanceReconciliationLine> lines = new ArrayList<>();
}
