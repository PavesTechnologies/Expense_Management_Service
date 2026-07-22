package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "receipt_ocr")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ReceiptOcr {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ocr_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID ocrId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id", nullable = false)
    @ToString.Exclude
    private Receipt receipt;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "receipt_date")
    private LocalDate receiptDate;

    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 255)
    private String currencyCode;

    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
