package com.expense_management_service.dto.response;

import com.expense_management_service.enums.LineItemReviewStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Current-submission-cycle review status for one ExpenseSplit (Phase 7) - the split-aware sibling of {@link LineItemReviewResponse}. */
public record SplitReviewResponse(
        UUID splitId,
        UUID reviewId,
        UUID lineItemId,
        UUID costCenterId,
        String costCenterCode,
        LineItemReviewStatus status,
        String comment,
        String actedBy,
        LocalDateTime actionedAt,
        Integer levelOrder,
        String levelName,
        String displayName
) {
}
