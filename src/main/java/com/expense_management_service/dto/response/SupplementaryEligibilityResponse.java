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
        boolean hasCashAdvance
) {
}
