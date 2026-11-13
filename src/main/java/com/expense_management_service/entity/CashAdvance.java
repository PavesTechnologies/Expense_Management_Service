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

    /** Expected trip start the advance was requested for. Prefills the expense report. Null when not given. */
    @Column(name = "expected_trip_start_date")
    private LocalDate expectedTripStartDate;

    /** Expected trip end the advance was requested for. Null when not given. */
    @Column(name = "expected_trip_end_date")
    private LocalDate expectedTripEndDate;

    @Column(name = "outstanding_balance", precision = 19, scale = 4)
    private BigDecimal outstandingBalance;

    /**
     * Balance carried into this advance from earlier advances (carry-forward). It is part of the advance amount: Finance
     * pays the amount less this, because the employee already holds it. Changed only by an APPLIED carry-forward or its
     * reversal. Null on older rows, which means zero.
     */
    @Column(name = "carried_in_amount", precision = 19, scale = 4)
    private BigDecimal carriedInAmount;

    /** Balance this advance has carried out to other advances. Changed only by an APPLIED carry-forward or its reversal. */
    @Column(name = "carried_out_amount", precision = 19, scale = 4)
    private BigDecimal carriedOutAmount;

    /** Unused balance marked ready to carry forward to the employee's next approved advance. Null means none is ready. */
    @Column(name = "carry_forward_ready_amount", precision = 19, scale = 4)
    private BigDecimal carryForwardReadyAmount;

    /**
     * Amount frozen at final approval. Finance may not change it afterwards. Null on rows approved before this was stored.
     */
    @Column(name = "approved_amount", precision = 19, scale = 4)
    private BigDecimal approvedAmount;

    /**
     * Cash actually paid out at disbursement (approved amount minus any carried-in balance). Kept separately from
     * {@link #approvedAmount} so an audit can show whether Finance paid exactly what was approved.
     */
    @Column(name = "disbursed_amount", precision = 19, scale = 4)
    private BigDecimal disbursedAmount;

    /** The amount the employee asked for. Kept when the manager reduces the amount, so the change stays visible. */
    @Column(name = "requested_amount", precision = 19, scale = 4)
    private BigDecimal requestedAmount;

    /** Manager's recommendation: FULL_ADVANCE, REDUCED or SET_OFF. Null until the manager decides. */
    @Column(name = "recommended_treatment", length = 30)
    private String recommendedTreatment;

    @Column(name = "recommended_amount", precision = 19, scale = 4)
    private BigDecimal recommendedAmount;

    /** For SET_OFF: the earlier advance whose outstanding balance is recommended to be set off. */
    @Column(name = "recommended_source_advance_id", length = 36)
    private String recommendedSourceAdvanceId;

    @Column(name = "recommended_set_off_amount", precision = 19, scale = 4)
    private BigDecimal recommendedSetOffAmount;

    @Column(name = "manager_justification", columnDefinition = "TEXT")
    private String managerJustification;

    @Column(name = "manager_decided_by", length = 255)
    private String managerDecidedBy;

    @Column(name = "manager_decided_at")
    private LocalDateTime managerDecidedAt;

    /** Set when the employee's exposure was above the configured limit and the manager approved anyway. */
    @Column(name = "exposure_exception_justification", columnDefinition = "TEXT")
    private String exposureExceptionJustification;

    @Column(name = "exposure_exception_by", length = 255)
    private String exposureExceptionBy;

    @Column(name = "exposure_exception_at")
    private LocalDateTime exposureExceptionAt;

    /** Finance's authorization of the exception, recorded at final approval. */
    @Column(name = "exposure_exception_authorized_by", length = 255)
    private String exposureExceptionAuthorizedBy;

    @Column(name = "exposure_exception_authorized_at")
    private LocalDateTime exposureExceptionAuthorizedAt;

    @Column(name = "deferred_reason", columnDefinition = "TEXT")
    private String deferredReason;

    @Column(name = "deferred_at")
    private LocalDateTime deferredAt;

    /** Reason given when the request was rejected. Shown to the employee. Cleared when the request is resubmitted. */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /** COMPLETE_NEW or ADVANCE_PLUS_OUTSTANDING. Null on rows created before request types, read as COMPLETE_NEW. */
    @Column(name = "request_type", length = 40)
    private String requestType;

    /** Only for ADVANCE_PLUS_OUTSTANDING: the amount needed now, on top of the eligible outstanding balance. */
    @Column(name = "additional_amount_requested", precision = 19, scale = 4)
    private java.math.BigDecimal additionalAmountRequested;

    /** Total outstanding across open advances when the request was made. Display only; never used for accounting. */
    @Column(name = "outstanding_amount_snapshot", precision = 19, scale = 4)
    private java.math.BigDecimal outstandingAmountSnapshot;

    /** Eligible outstanding balance plus the amount needed now, as calculated when the request was made. */
    @Column(name = "total_advance_allocation", precision = 19, scale = 4)
    private java.math.BigDecimal totalAdvanceAllocation;

    /** The employee's reason for a separate advance. Required when an outstanding balance exists and the type is COMPLETE_NEW. */
    @Column(name = "employee_justification", columnDefinition = "TEXT")
    private String employeeJustification;

    /** Total balance rolled into this request from earlier advances, as authorised by Finance. Null until approved. */
    @Column(name = "authorized_rollover_amount", precision = 19, scale = 4)
    private java.math.BigDecimal authorizedRolloverAmount;

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