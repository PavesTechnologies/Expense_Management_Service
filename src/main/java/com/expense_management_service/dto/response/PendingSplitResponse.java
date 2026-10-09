package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Full review context for one ExpenseSplit at the caller's own combined Cost Center Owner assignment (Phase 7) - the split-aware sibling of {@link PendingLineItemResponse}. */
public record PendingSplitResponse(
        UUID splitId,
        UUID reviewId,
        UUID lineItemId,
        UUID costCenterId,
        String costCenterCode,
        String costCenterName,
        BigDecimal allocatedAmount
) {
}
