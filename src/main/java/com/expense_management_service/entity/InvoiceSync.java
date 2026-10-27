package com.expense_management_service.entity;

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
}
