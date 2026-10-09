package com.expense_management_service.service;

import com.expense_management_service.dto.response.OcrStatusResponse;
import com.expense_management_service.dto.response.ReceiptOcrResponse;

import java.util.UUID;

/**
 * Orchestrates the OCR pipeline for a single receipt: sequences the Textract call, response
 * parsing, persistence, and status transitions. Does not talk to AWS or Textract's response
 * shape directly — delegates to {@link TextractService}, {@link TextractResponseParser}, and
 * {@code ReceiptOcrRepository}.
 * <p>
 * Never creates an {@code ExpenseLineItem} — the employee always reviews and saves manually
 * through the existing Manual Entry screen.
 */
public interface OCRService {

    /**
     * Runs a fresh OCR attempt for the given receipt: marks it {@code PROCESSING}, calls
     * Textract, parses the result, and persists a new {@code ReceiptOcr} row as either
     * {@code COMPLETED} or {@code FAILED}. Never throws on a Textract failure — a failed
     * attempt is a normal, successfully-returned result (see Feature 7: the employee must be
     * able to continue with manual entry), not an error response.
     *
     * @param receiptId the receipt to process — must already exist and be stored in S3
     * @return the persisted extraction result, whichever status it ended in
     */
    ReceiptOcrResponse processReceipt(UUID receiptId);

    /**
     * Re-runs OCR for a receipt whose latest attempt failed, reusing the receipt's existing S3
     * object — never re-uploads.
     *
     * @throws com.expense_management_service.common.exception.BusinessRuleViolationException if
     *                                                                                          the latest attempt for this receipt did not fail
     */
    ReceiptOcrResponse retryOcr(UUID receiptId);

    /** The most recent extraction attempt for a receipt, for populating the Manual Entry screen. */
    ReceiptOcrResponse getLatestResult(UUID receiptId);

    /** Lightweight receipt-level status, for polling from the "Loading" step of the upload workflow. */
    OcrStatusResponse getStatus(UUID receiptId);

    /**
     * Records that an employee changed an OCR-extracted value before saving the expense line
     * item. An audit-only extension point: deliberately does not touch
     * {@code ExpenseLineItemService} or the line item save itself — the frontend calls this
     * directly (see {@code OcrController}) when it detects the employee edited a pre-filled
     * value, decoupling the override audit trail from the existing, untouched line-item save
     * flow.
     *
     * @param fieldName        which pre-filled field was changed, e.g. "amount"
     * @param originalValue    the OCR-suggested value, as a string
     * @param overriddenValue  the value the employee actually saved, as a string
     */
    void recordOverride(UUID receiptId, String fieldName, String originalValue, String overriddenValue);

    /** Same as {@link #recordOverride(UUID, String, String, String)}, with an optional free-text reason for the change. */
    void recordOverride(UUID receiptId, String fieldName, String originalValue, String overriddenValue, String reason);
}
