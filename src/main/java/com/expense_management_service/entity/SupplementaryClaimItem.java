package com.expense_management_service.entity;

import com.expense_management_service.enums.EmployeePaymentMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One missing expense on a supplementary claim. Receipts attach to it through the receipt's claim-item link. */
@Entity
@Table(name = "supplementary_claim_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "claim")
public class SupplementaryClaimItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "claim_item_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID claimItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private SupplementaryExpenseClaim claim;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_payment_method", length = 32)
    private EmployeePaymentMethod employeePaymentMethod;

    @Column(name = "notes", length = 500)
    private String notes;
}
