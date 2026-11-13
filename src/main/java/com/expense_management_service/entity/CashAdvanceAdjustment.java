package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
// Uniqueness of ACTIVE offsets per (advance, report) is enforced by the database on the generated column
// active_offset_key (see migration V39). Reversed rows keep the key NULL, so a reversed offset can be reapplied
// as a new row while the original stays in place.
@Table(name = "cash_advance_adjustment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CashAdvanceAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "adjustment_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID adjustmentId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advance_id", nullable = false)
    @ToString.Exclude
    private CashAdvance cashAdvance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    @Column(name = "adjusted_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal adjustedAmount;

    @Column(name = "adjusted_by", length = 255)
    private String adjustedBy;

    @Column(name = "adjusted_at")
    private LocalDateTime adjustedAt;

    @Column(name = "payment_method", length = 255)
    private String paymentMethod;

    @Column(name = "transaction_reference", length = 255)
    private String transactionReference;

    @Column(name = "payment_date")
    private LocalDateTime paymentDate;

    @Column(name = "disbursement_status", length = 255)
    private String disbursementStatus;

    @Column(name = "idempotency_key", length = 255, unique = true)
    private String idempotencyKey;

    /**
     * Set when the offset is reversed. The row is kept for audit and is never deleted. Reversed rows are excluded from
     * every balance and verification total, via {@link #isActive()}.
     */
    @Column(name = "reversed", nullable = false)
    @Builder.Default
    private boolean reversed = false;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "reversed_by", length = 255)
    private String reversedBy;

    @Column(name = "reversal_reason", length = 1000)
    private String reversalReason;

    /**
     * Database-generated key: "advanceId:reportId" while the offset is active, NULL once reversed. Read-only here.
     * The unique index on it prevents two active offsets for the same advance and report.
     */
    @Column(name = "active_offset_key", length = 80, insertable = false, updatable = false)
    private String activeOffsetKey;

    /** True when this offset still counts against the advance balance. */
    public boolean isActive() {
        return !reversed;
    }
}
