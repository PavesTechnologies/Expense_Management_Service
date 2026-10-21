package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "manager_id", length = 255)
    private String managerId;

    /*
     * Cost Center selected for this cash advance.
     *
     * The cash advance budget is validated against the
     * CostCenterBudget belonging to this CostCenter.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cost_center_id")
    @ToString.Exclude
    private CostCenter costCenter;

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

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "needed_by_date")
    private LocalDate neededByDate;

    @Column(name = "status", length = 255)
    private String status;

    @Column(name = "settlement_due_date")
    private LocalDate settlementDueDate;

    @Column(name = "outstanding_balance", precision = 19, scale = 4)
    private BigDecimal outstandingBalance;

    @Column(name = "approved_amount", precision = 19, scale = 4)
    private BigDecimal approvedAmount;

    /** Fiscal year whose budget this advance was charged to at final approval. Null if never charged. */
    @Column(name = "budget_fiscal_year", length = 10)
    private String budgetFiscalYear;

    @Column(name = "settlement_status", length = 255)
    private String settlementStatus;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settled_by", length = 255)
    private String settledBy;

    /** When Finance disbursed the advance. The 30-day expense window starts here; null means the clock has not started. */
    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    /** BANK_TRANSFER or CASH. */
    @Column(name = "disbursement_mode", length = 20)
    private String disbursementMode;

    /** Bank transaction reference (bank transfer). */
    @Column(name = "disbursement_reference", length = 255)
    private String disbursementReference;

    @Column(name = "disbursement_payment_date")
    private LocalDate disbursementPaymentDate;

    /** Who received the cash (cash). */
    @Column(name = "disbursement_acknowledged_by", length = 255)
    private String disbursementAcknowledgedBy;

    /** Storage key of the uploaded proof: the signed cash acknowledgment (cash) or payment screenshot (UPI). */
    @Column(name = "disbursement_proof_reference", length = 255)
    private String disbursementProofReference;

    /** Bank and beneficiary account the transfer went to (bank transfer). */
    @Column(name = "disbursement_transfer_details", columnDefinition = "TEXT")
    private String disbursementTransferDetails;

    /** Payee UPI ID the payment was sent to (UPI). */
    @Column(name = "disbursement_payee_upi_id", length = 255)
    private String disbursementPayeeUpiId;

    /** Optional note Finance added when recording the payout. */
    @Column(name = "disbursement_confirmation_note", columnDefinition = "TEXT")
    private String disbursementConfirmationNote;

    /** Finance override: replaces disbursed_at + 30 days when set. */
    @Column(name = "expense_deadline_extended_until")
    private LocalDate expenseDeadlineExtendedUntil;

    /** Finance override: when true the expense window is open regardless of date. */
    @Column(name = "expense_deadline_reopened", nullable = false)
    private boolean expenseDeadlineReopened;

    /** Set once the 7-day reminder has been sent, so it goes out only once. */
    @Column(name = "deadline_reminder_sent_at")
    private LocalDateTime deadlineReminderSentAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(
            mappedBy = "cashAdvance",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @Builder.Default
    @ToString.Exclude
    private List<CashAdvanceAdjustment> cashAdvanceAdjustments = new ArrayList<>();

    @OneToMany(
            mappedBy = "cashAdvance",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE}
    )
    @Builder.Default
    @ToString.Exclude
    private List<CashAdvanceRepayment> repayments = new ArrayList<>();
}