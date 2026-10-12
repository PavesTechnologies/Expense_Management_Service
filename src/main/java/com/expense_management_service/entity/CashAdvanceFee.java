package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The fee charged once when a cash advance is finally approved. One row per advance: the UNIQUE
 * constraint on {@code advance_id} is the database-level guard against recording a second fee.
 * {@code amount} is the fee already deducted from the cost center budget; {@code expenseReport} is
 * the company-paid report that records it as an expense (null when the fee is zero).
 */
@Entity
@Table(name = "cash_advance_fee", uniqueConstraints = {
        @UniqueConstraint(name = "uk_cash_advance_fee_advance", columnNames = "advance_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashAdvanceFee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "fee_id", updatable = false, nullable = false)
    private UUID feeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false)
    @ToString.Exclude
    private CashAdvance advance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id")
    @ToString.Exclude
    private CostCenter costCenter;

    /** Fee rate used, as a fraction (0.02 = 2%). Stored so a later rate change cannot alter a past fee. */
    @Column(name = "fee_rate", precision = 9, scale = 6, nullable = false)
    private BigDecimal feeRate;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_report_id")
    @ToString.Exclude
    private ExpenseReport expenseReport;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
