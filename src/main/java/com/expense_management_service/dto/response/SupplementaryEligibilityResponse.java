package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Whether a settled report can take a supplementary claim, with the reason when it cannot. */
public record SupplementaryEligibilityResponse(
        UUID reportId,
        String reportNumber,
        boolean eligible,
        String reason,
        BigDecimal amountOwed,
        boolean hasCashAdvance,
        /** When the advance's balance was carried forward: the advance a claim will be set against (null otherwise). */
        java.util.UUID settleAgainstAdvanceId,
        String settleAgainstAdvanceTitle,
        /** What that advance still holds: up to this much is set against it, the rest is paid by AP. */
        BigDecimal settleAgainstRemaining
) {
}
