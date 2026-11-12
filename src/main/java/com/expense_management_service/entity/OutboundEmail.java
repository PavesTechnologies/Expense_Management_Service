package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An email waiting to be sent. Every notice and report is written here first, so nothing is lost when the mail
 * transport is unavailable. A row stays PENDING until a transport accepts it; repeated failures mark it FAILED.
 */
@Entity
@Table(name = "outbound_email")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class OutboundEmail {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    /** Withdrawn before delivery. The dispatcher only picks up PENDING rows, so this is never sent. */
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbound_email_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID outboundEmailId;

    @Column(name = "to_address", nullable = false, length = 255)
    private String toAddress;

    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** LONGTEXT: payroll and notice emails can be far longer than 255 characters. */
    @Lob
    @Column(name = "body", nullable = false, columnDefinition = "LONGTEXT")
    private String body;

    /** What the email is about, e.g. CASH_ADVANCE_RECOVERY_NOTICE or CASH_ADVANCE_PAYROLL_REPORT. */
    @Column(name = "email_type", nullable = false, length = 64)
    private String emailType;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
