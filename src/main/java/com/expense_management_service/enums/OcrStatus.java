package com.expense_management_service.enums;

/**
 * Lifecycle status of a receipt's OCR + review journey (EP03-S4).
 * <p>
 * Two entities share this vocabulary with distinct ownership:
 * <ul>
 *     <li>{@code Receipt.ocrStatus} (plain {@code String}, unchanged storage) — the full
 *     receipt-level lifecycle, written by {@code OCRServiceImpl} for
 *     {@link #UPLOADED}/{@link #PROCESSING}/{@link #OCR_COMPLETED}/{@link #RETRY_AVAILABLE},
 *     and by {@code ReceiptConfirmationServiceImpl}/the report-submit flow for
 *     {@link #REVIEW_PENDING}/{@link #VERIFIED}/{@link #SUBMITTED}.</li>
 *     <li>{@code ReceiptOcr.processingStatus} (this enum, {@code @Enumerated(STRING)})
 *     — one extraction attempt's status. Only ever {@link #PROCESSING},
 *     {@link #OCR_COMPLETED}, or {@link #FAILED} — a {@code ReceiptOcr} row is created
 *     the moment an attempt starts, so it never observes {@link #UPLOADED}, and the
 *     review/verify/submit values describe what happens to the receipt afterward, not
 *     the attempt itself.</li>
 * </ul>
 * Written only by {@code OCRServiceImpl} (and, for the review/verify/submit tail,
 * {@code ReceiptConfirmationServiceImpl}) — no other class transitions this status, which is
 * what keeps the two fields from drifting out of sync.
 */
public enum OcrStatus {

    /** Receipt uploaded, no extraction attempt has started yet. Receipt-level only. */
    UPLOADED,

    /** A Textract call is in flight for this attempt. */
    PROCESSING,

    /** Extraction succeeded; a {@code ReceiptOcr} row now holds real values. Machine-only success — no employee has reviewed it yet. */
    OCR_COMPLETED,

    /** Extraction failed and a retry is available; {@code failureReason} on the attempt explains why. Employee can retry or continue manually. Receipt-level only — the failed attempt itself is recorded as {@link #FAILED}. */
    RETRY_AVAILABLE,

    /** Employee has the Manual Entry / review screen open, pre-filled with OCR values. Receipt-level only. */
    REVIEW_PENDING,

    /** Employee confirmed — an expense line item now exists (created or linked). Receipt-level only. */
    VERIFIED,

    /** The parent expense report has been submitted for approval. Receipt-level only. */
    SUBMITTED,

    /** This one extraction attempt failed; {@code failureReason} explains why. Attempt-level only. */
    FAILED
}
