package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A receipt attached to an expense after its report was approved, reimbursed or closed. The line's amount, status and
 * payment are not part of this record and are never changed by it.
 */
public record LateReceiptResponse(
        UUID receiptId,
        UUID reportId,
        String reportNumber,
        UUID lineItemId,
        String employeeId,
        /** The expense's original date, from the line item. */
        LocalDate expenseDate,
        /** The claimed amount on the line, for comparison with the receipt. */
        BigDecimal lineAmount,
        String originalFileName,
        String uploadedBy,
        LocalDateTime uploadedAt,
        /** The date printed on the receipt, as entered by the employee. */
        LocalDate actualReceiptDate,
        String lateReason,
        /** OCR progress for automatic extraction: UPLOADED, PROCESSING, OCR_COMPLETED, RETRY_AVAILABLE, ... */
        String ocrStatus,
        /** PENDING_REVIEW, VERIFIED or REJECTED. */
        String verificationStatus,
        String verifiedBy,
        LocalDateTime verifiedAt,
        String verificationNote,
        /** Warning or blocking duplicate state, from the same check used for claimed receipts. */
        String duplicateStatus,
        String duplicateMessage
) {
}
