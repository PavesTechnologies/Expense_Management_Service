package com.expense_management_service.dto.response;

import com.expense_management_service.enums.FinanceVerificationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Current-submission-cycle Finance review status for one line item - the Finance-level equivalent of {@code LineItemReviewResponse}. */
public record FinanceLineItemReviewResponse(
        UUID lineItemId,
        UUID reviewId,
        FinanceVerificationStatus status,
        String comment,
        String actedBy,
        LocalDateTime actionedAt,
        String glAccountCodeSnapshot,
        Boolean policyExceptionResolvedFlag,
        Boolean receiptValidatedFlag,
        Integer levelOrder,
        String levelName,
        String displayName
) {
}
