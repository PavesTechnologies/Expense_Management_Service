package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "cash_advance_repayment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CashAdvanceRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repayment_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID repaymentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "advance_id", nullable = false)
    @ToString.Exclude
    private CashAdvance cashAdvance;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 255)
    private String paymentMethod;

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Column(name = "repaid_by", length = 255)
    private String repaidBy;

    @Column(name = "repaid_at")
    private LocalDateTime repaidAt;

    @Lob
    @Column(name = "notes")
    private String notes;
}
