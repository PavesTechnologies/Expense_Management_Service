package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One allocation of an unspent balance from a source cash advance to a destination advance (carry-forward).
 * Status: PROPOSED to APPLIED (approved by a Finance user other than the requester), or REJECTED from PROPOSED,
 * or REVERSED from APPLIED. Rows are never deleted; reversal is a status change with its own audit entry.
 */
@Entity
@Table(name = "cash_advance_carry_forward")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CashAdvanceCarryForward {

    public static final String PROPOSED = "PROPOSED";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "carry_forward_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID carryForwardId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_advance_id", nullable = false)
    @ToString.Exclude
    private CashAdvance sourceAdvance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_advance_id", nullable = false)
    @ToString.Exclude
    private CashAdvance destinationAdvance;

    @Column(name = "employee_id", nullable = false, length = 255)
    private String employeeId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    @Column(name = "rejected_by", length = 255)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "reversed_by", length = 255)
    private String reversedBy;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;
}
