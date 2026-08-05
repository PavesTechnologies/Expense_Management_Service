package com.expense_management_service.entity;

import com.expense_management_service.enums.OcrStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    @Column(name = "receipt_time")
    private LocalTime receiptTime;

    /** The receipt's grand total — exposed on the API as {@code totalAmount}; kept named {@code amount} at the entity/column level to avoid an unnecessary physical rename. */
    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 255)
    private String currencyCode;

    @Column(name = "subtotal", precision = 19, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "invoice_number", length = 255)
    private String invoiceNumber;

    /** Total tax — sum of CGST/SGST/IGST when present (Indian GST receipts), otherwise the single TAX line. Never just one GST component. */
    @Column(name = "tax_amount", precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "confidence_score", precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    /** Status of this one extraction attempt — see {@link OcrStatus} javadoc for the full ownership contract. */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 30, nullable = false)
    @Builder.Default
    private OcrStatus processingStatus = OcrStatus.PROCESSING;

    /** Populated only when {@code processingStatus == FAILED} — explains why, for support/employee visibility. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** Wall-clock time the Textract call + parsing took, in milliseconds. */
    @Column(name = "processing_duration_ms")
    private Long processingDurationMs;

    /** Which OCR engine produced this attempt — always "AWS_TEXTRACT" today, but tracked per-row for when a second engine is ever introduced. */
    @Column(name = "ocr_engine", length = 50)
    private String ocrEngine;

    /** The Textract API/operation version used, e.g. "AnalyzeExpense". */
    @Column(name = "ocr_version", length = 50)
    private String ocrVersion;
}
