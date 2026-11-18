package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
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

    /** The report this receipt belongs to from the moment it's uploaded — set before any line item exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    /**
     * Whose expense this is — the report owner's employee id, not necessarily the caller who
     * performed the upload (an Admin may upload on an employee's behalf). Stored directly rather
     * than only reachable via {@code report.getEmployeeId()} so ownership/duplicate-detection
     * checks never depend on a lazy association walk.
     */
    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    /**
     * Nullable: a receipt can exist, and OCR can run, before any line item does. Populated once
     * the employee confirms/links a line item (see {@code ReceiptConfirmationService}).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = true)
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

    /** {@link #ATTACHMENT_ORIGINAL} for a receipt uploaded with its claim; {@link #ATTACHMENT_LATE} for one attached after approval. */
    public static final String ATTACHMENT_ORIGINAL = "ORIGINAL";
    public static final String ATTACHMENT_LATE = "LATE";
    /** Uploaded for a supplementary claim item. Its report link is the original report; it is never a line item receipt. */
    public static final String ATTACHMENT_SUPPLEMENTARY = "SUPPLEMENTARY";

    public static final String VERIFICATION_PENDING = "PENDING_REVIEW";
    public static final String VERIFICATION_VERIFIED = "VERIFIED";
    public static final String VERIFICATION_REJECTED = "REJECTED";

    @Column(name = "attachment_kind", length = 20, nullable = false)
    @Builder.Default
    private String attachmentKind = ATTACHMENT_ORIGINAL;

    /** The date printed on the late receipt, entered by the employee. Separate from the line's expense date and from {@link #uploadedAt}. */
    @Column(name = "actual_receipt_date")
    private LocalDate actualReceiptDate;

    /** Why the receipt was submitted after the report was approved. Required for a late receipt. */
    @Column(name = "late_reason", length = 500)
    private String lateReason;

    /** Finance/AP review of a late receipt: PENDING_REVIEW, VERIFIED or REJECTED. Null for an original receipt. */
    @Column(name = "verification_status", length = 20)
    private String verificationStatus;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ReceiptOcr> receiptOcrs = new ArrayList<>();

    /**
     * The supplementary claim item this receipt supports, when it belongs to a claim. Null otherwise. One receipt row
     * points at one item at most; the service locks the row so a receipt cannot be attached to two claims at once.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplementary_claim_item_id", nullable = true)
    @ToString.Exclude
    private SupplementaryClaimItem supplementaryClaimItem;
}
