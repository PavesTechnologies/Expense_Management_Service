package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A request to release a receipt that Finance rejected, so it can be used on a new claim. Two different Finance users
 * take part: the requester asks, and a second user approves. The original rejection is never changed.
 */
@Entity
@Table(name = "receipt_release")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptRelease {

    public static final String REQUESTED = "RELEASE_REQUESTED";
    public static final String RELEASED = "RELEASED";
    public static final String RELEASE_REJECTED = "RELEASE_REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "release_id", updatable = false, nullable = false)
    private UUID releaseId;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "request_reason", nullable = false, length = 500)
    private String requestReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "decided_by", length = 255)
    private String decidedBy;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** Set when the released receipt is attached to a claim. A consumed release cannot attach again. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
}
