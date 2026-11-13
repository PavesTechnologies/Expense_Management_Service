package com.expense_management_service.entity;

import com.expense_management_service.enums.PaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One bank payment covering one or more expense reports. The bank reference is unique across batches, so one reference
 * never stands for two independent transactions. Individual report payments do not use a batch.
 */
@Entity
@Table(name = "ap_payment_batch", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ap_payment_batch_reference", columnNames = {"bank_reference"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApPaymentBatch {

    public static final String RECORDED = "RECORDED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "bank_reference", length = 100, nullable = false)
    private String bankReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30, nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "remarks", length = 500)
    private String remarks;

    @Column(name = "recorded_by", length = 255, nullable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = false)
    @Builder.Default
    private List<ApPaymentAllocation> allocations = new ArrayList<>();
}
