package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The part of one expense report paid by one batch. {@code payableBefore} and {@code payableAfter} record what the report
 * still owed before and after this allocation, so the report's payment history can be read without recalculation.
 */
@Entity
@Table(name = "ap_payment_allocation", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ap_allocation_batch_report", columnNames = {"batch_id", "report_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    @ToString.Exclude
    private ApPaymentBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "payable_before", precision = 19, scale = 4, nullable = false)
    private BigDecimal payableBefore;

    @Column(name = "payable_after", precision = 19, scale = 4, nullable = false)
    private BigDecimal payableAfter;
}
