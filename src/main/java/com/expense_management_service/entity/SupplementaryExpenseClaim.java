package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A claim for expenses that were never entered on a settled report. It is a separate financial transaction that links
 * to the original report and never changes it.
 */
@Entity
@Table(name = "supplementary_expense_claim")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"items", "original"})
public class SupplementaryExpenseClaim {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_MANAGER = "PENDING_MANAGER";
    public static final String MANAGER_REJECTED = "MANAGER_REJECTED";
    public static final String PENDING_FINANCE = "PENDING_FINANCE";
    public static final String FINANCE_REJECTED = "FINANCE_REJECTED";
    public static final String CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED";
    public static final String APPROVED = "APPROVED";
    public static final String PAYMENT_PENDING = "PAYMENT_PENDING";
    public static final String SETTLED = "SETTLED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "claim_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID claimId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "claim_number", nullable = false, unique = true, length = 32)
    private String claimNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_report_id", nullable = false)
    private ExpenseReport original;

    /** The employee the claim is for. */
    @Column(name = "employee_id", nullable = false, length = 255)
    private String employeeId;

    /** The user who created the claim: the employee, or an authorised Finance user on their behalf. */
    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "created_on_behalf", nullable = false)
    @Builder.Default
    private boolean createdOnBehalf = false;

    @Column(name = "creation_reason", length = 500)
    private String creationReason;

    /** Set when Finance created the claim: the employee confirms it before it goes to Manager review. */
    @Column(name = "employee_confirmed_at")
    private LocalDateTime employeeConfirmedAt;

    @Column(name = "late_reason_code", nullable = false, length = 40)
    private String lateReasonCode;

    @Column(name = "late_reason_text", columnDefinition = "TEXT")
    private String lateReasonText;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /** Linked cash advance, for review only. A claim never changes the advance. */
    @Column(name = "original_cash_advance_id")
    private UUID originalCashAdvanceId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SupplementaryClaimItem> items = new ArrayList<>();
}
