package com.expense_management_service.dto.response;

import com.expense_management_service.enums.LineItemReviewStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** Current-submission-cycle review status for one line item - backs the "what needs correcting, and why" requirement (§14 backend gaps). */
public record LineItemReviewResponse(
        UUID lineItemId,
        UUID reviewId,
        LineItemReviewStatus status,
        String comment,
        String actedBy,
        LocalDateTime actionedAt,
        Integer levelOrder,
        String levelName,
        String displayName
) {
}
