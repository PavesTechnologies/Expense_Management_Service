package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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

    /** How the repayment was paid: CASH, BANK_TRANSFER or UPI (same modes as a disbursement). */
    @Column(name = "payment_mode", length = 32)
    private String paymentMode;

    @Column(name = "received_by_name", length = 255)
    private String receivedByName;

    /** Date of the bank or UPI transfer. Null for cash. */
    @Column(name = "transfer_date")
    private LocalDate transferDate;

    @Lob
    @Column(name = "transfer_details")
    private String transferDetails;

    @Column(name = "payee_upi_id", length = 255)
    private String payeeUpiId;

    /** Storage key of the cash acknowledgment or UPI screenshot. Null for bank transfers. */
    @Column(name = "proof_reference", length = 512)
    private String proofReference;

    @Lob
    @Column(name = "confirmation_note")
    private String confirmationNote;
}
