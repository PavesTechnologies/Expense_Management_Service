package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One report in Finance Verification's "Verified" or "Queried" history tab - the completed
 * counterpart to {@link FinanceQueueItemResponse} (which only ever holds PENDING reports).
 * {@code verificationStatus} is always {@code VERIFIED} or {@code QUERIED} - there is no
 * "REJECTED" outcome at this level (a query sends the report back for correction instead of
 * terminating it).
 */
public record FinanceHistoryItemResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        BigDecimal totalAmount,
        String currencyCode,
        String costCenterName,
        String reportStatus,
        String verificationStatus,
        Integer levelOrder,
        String actedBy,
        LocalDateTime actionedAt,
        String comment,
        /** Where the reimbursement stands after verification: NONE, APPROVED_FOR_PAYMENT (with AP) or PAYMENT_COMPLETED. */
        String paymentRoutingStatus,
        LocalDateTime paymentCompletedAt,
        /** NOT_APPLICABLE, or PENDING/COMPLETED for a client-billable report's invoice handoff. */
        String invoiceHandoffStatus
) {
}
