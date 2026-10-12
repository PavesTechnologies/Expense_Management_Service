package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Server-calculated utilization of one cash advance. The frontend displays these values as they are and
 * never recomputes them. All money is in the advance's currency (line amounts are read in base currency).
 */
public record CashAdvanceUtilizationResponse(
        UUID advanceId,
        String currencyCode,
        BigDecimal advanceAmount,
        /** Sum of every line on the linked reports. */
        BigDecimal claimedAmount,
        /** Lines whose line-level review is fully APPROVED. */
        BigDecimal approvedAmount,
        /** Lines sent back for correction (NEEDS_CORRECTION). */
        BigDecimal returnedAmount,
        /** Lines still awaiting a decision, including lines never reviewed. */
        BigDecimal pendingReviewAmount,
        /** Active budget reservations held by the linked reports (submitted, not yet paid). */
        BigDecimal submittedReservationAmount,
        /** Sum of applied offsets: approved expense that has been set against the advance. */
        BigDecimal approvedUtilization,
        BigDecimal totalRepaidAmount,
        int repaymentCount,
        /** Stored outstanding balance on the advance. */
        BigDecimal outstandingBalance,
        /** True when advanceAmount - approvedUtilization - totalRepaidAmount equals the stored outstanding balance. */
        boolean balanceConsistent,
        String advanceStatus,
        /** CLOSED, REPAYMENT_DUE, REIMBURSEMENT_DUE or PENDING_CLOSURE (zero balance, not yet closed). */
        String closureStatus,
        boolean closed,
        List<LinkedReport> linkedReports,
        List<CategoryUtilization> categories
) {

    public record LinkedReport(
            UUID reportId,
            String reportNumber,
            String reportStatus,
            BigDecimal claimedAmount,
            BigDecimal reservedAmount
    ) {
    }

    public record CategoryUtilization(
            UUID categoryId,
            String categoryCode,
            String categoryName,
            boolean receiptRequired,
            int lineCount,
            /** Lines in a receipt-required category with no receipt attached. */
            int missingReceiptCount,
            BigDecimal claimedAmount,
            BigDecimal approvedAmount,
            BigDecimal returnedAmount,
            BigDecimal pendingReviewAmount,
            List<LineUtilization> lines
    ) {
    }

    public record LineUtilization(
            UUID lineItemId,
            UUID reportId,
            LocalDate expenseDate,
            String merchantName,
            String description,
            BigDecimal claimedAmount,
            /** APPROVED, RETURNED, PENDING_REVIEW or NOT_REVIEWED. */
            String verificationStatus,
            List<ReceiptLink> receipts
    ) {
    }

    /** Metadata only. The frontend fetches a pre-signed URL from /xms/employee/receipts/{receiptId}/view on demand. */
    public record ReceiptLink(
            UUID receiptId,
            String originalFileName,
            String contentType
    ) {
    }
}
