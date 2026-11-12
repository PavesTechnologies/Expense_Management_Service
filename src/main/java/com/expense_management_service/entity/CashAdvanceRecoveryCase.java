package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Recovery of a pending cash advance amount. Route 2: a refund notice is sent and the employee has the grace period
 * to return the money. Route 3: if the grace period passes with the balance still open, a recovery report is sent to
 * payroll and the case becomes SENT_TO_PAYROLL. That is not a completed deduction; the deduction is done in payroll.
 */
@Entity
@Table(name = "cash_advance_recovery_case")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "advance")
public class CashAdvanceRecoveryCase {

    public static final String NOTICE_SENT = "NOTICE_SENT";
    public static final String RETURNED = "RETURNED";
    public static final String SENT_TO_PAYROLL = "SENT_TO_PAYROLL";
    /** Finance withdrew the notice before any payroll report included it. The advance keeps its balance. */
    public static final String CANCELLED = "CANCELLED";
    /** Finance recorded Payroll's deduction result for a case that was sent to payroll. */
    public static final String PAYROLL_RECORDED = "PAYROLL_RECORDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "recovery_case_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID recoveryCaseId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "advance_id", nullable = false)
    private CashAdvance advance;

    @Column(name = "employee_id", nullable = false, length = 255)
    private String employeeId;

    /** Outstanding balance when the notice was sent. The live balance is read from the advance at escalation time. */
    @Column(name = "amount_at_notice", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountAtNotice;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "notice_sent_at", nullable = false)
    private LocalDateTime noticeSentAt;

    @Column(name = "notice_sent_by", nullable = false, length = 255)
    private String noticeSentBy;

    /** End of the grace period. After this moment, an open balance goes to payroll report. */
    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "payroll_report_sent_at")
    private LocalDateTime payrollReportSentAt;

    /** Why the notice was cancelled, when Finance cancels it. */
    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    /** Payroll's deduction for this case, as recorded by Finance. Posted to the balance once. */
    @Column(name = "payroll_actual_deducted", precision = 19, scale = 4)
    private BigDecimal payrollActualDeducted;

    @Column(name = "payroll_reference", length = 100)
    private String payrollReference;

    @Column(name = "payroll_recorded_at")
    private LocalDateTime payrollRecordedAt;

    @Column(name = "payroll_recorded_by", length = 255)
    private String payrollRecordedBy;
}
