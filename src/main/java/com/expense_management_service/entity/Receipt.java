package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "receipt_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID receiptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    /** Original file name as supplied by the browser, e.g. "taxi-receipt.pdf" — used for display and as the download file name. */
    @Column(name = "original_file_name", length = 255, nullable = false)
    private String originalFileName;

    /** Sanitized, de-duplicated file name actually stored in S3 (the last path segment of {@link #objectKey}). */
    @Column(name = "stored_file_name", length = 255, nullable = false)
    private String storedFileName;

    /** Full S3 object key, e.g. "receipts/{employeeId}/{reportId}/{lineItemId}/{uuid}-taxi-receipt.pdf". Never exposed to clients. */
    @Column(name = "object_key", length = 512, nullable = false)
    private String objectKey;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "file_size")
    private Integer fileSize;

    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "ocr_status", length = 255)
    private String ocrStatus;

    @Column(name = "file_hash", length = 255)
    private String fileHash;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ReceiptOcr> receiptOcrs = new ArrayList<>();
}
