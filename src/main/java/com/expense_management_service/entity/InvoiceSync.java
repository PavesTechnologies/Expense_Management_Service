package com.expense_management_service.entity;

import com.expense_management_service.enums.ArDeliveryStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoice_sync")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class InvoiceSync {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "sync_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID syncId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @Column(name = "invoice_reference", length = 255)
    private String invoiceReference;

    @Column(name = "sync_status", length = 255)
    private String syncStatus;

    @Column(name = "sync_date")
    private LocalDateTime syncDate;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Lob
    @Column(name = "remarks")
    private String remarks;

    /**
     * Delivery of this handoff to AR over RabbitMQ (see {@code ArBillingDeliveryServiceImpl}). Null for a
     * handoff recorded before delivery existed - AR pulled those. varchar, not a MySQL ENUM, so a new status
     * never needs DDL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ar_delivery_status", columnDefinition = "varchar(16)")
    private ArDeliveryStatus arDeliveryStatus;

    @Column(name = "ar_delivery_attempts")
    private Integer arDeliveryAttempts;

    @Column(name = "ar_last_attempt_at")
    private LocalDateTime arLastAttemptAt;

    /** When the broker confirmed the message onto AR's queue. */
    @Column(name = "ar_delivered_at")
    private LocalDateTime arDeliveredAt;

    /** Why the last attempt failed; cleared once delivered. */
    @Column(name = "ar_delivery_error", length = 500)
    private String arDeliveryError;
}
