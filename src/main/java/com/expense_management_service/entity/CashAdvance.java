package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "cash_advance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CashAdvance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "advance_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID advanceId;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    @ToString.Exclude
    private Currency currency;

    @Column(name = "base_amount", precision = 19, scale = 4)
    private BigDecimal baseAmount;

    @Lob
    @Column(name = "purpose")
    private String purpose;

    @Column(name = "status", length = 255)
    private String status;

    @Column(name = "settlement_due_date")
    private LocalDate settlementDueDate;

    @Column(name = "outstanding_balance", precision = 19, scale = 4)
    private BigDecimal outstandingBalance;

    @OneToMany(mappedBy = "cashAdvance", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @Builder.Default
    @ToString.Exclude
    private List<CashAdvanceAdjustment> cashAdvanceAdjustments = new ArrayList<>();
}
