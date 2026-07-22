package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record CostAllocationResponse(
        UUID allocationId,
        UUID lineItemId,
        UUID costCenterId,
        String costCenterName,
        BigDecimal allocationPercentage,
        BigDecimal allocationAmount,
        LocalDateTime createdAt
) {
}
