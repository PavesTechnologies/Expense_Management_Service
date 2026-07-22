package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
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
}
