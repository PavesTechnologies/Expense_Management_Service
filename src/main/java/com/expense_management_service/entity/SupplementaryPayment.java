package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AP payment request for one supplementary claim. Created when Finance approves the claim; the claim is SETTLED only
 * when AP confirms a payment on this row. The original expense report and its payment fields are never touched.
 */
@Entity
@Table(name = "supplementary_payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplementaryPayment {

    public static final String REQUESTED = "REQUESTED";
    public static final String PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "payment_id", updatable = false, nullable = false)
    private UUID paymentId;

    @Version
    private Long version;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false, unique = true)
    @ToString.Exclude
    private SupplementaryExpenseClaim claim;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "paid_by", length = 255)
    private String paidBy;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "bank_reference", length = 100, unique = true)
    private String bankReference;

    @Column(name = "payment_method", length = 32)
    private String paymentMethod;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "remarks", length = 500)
    private String remarks;
}
