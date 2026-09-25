package com.expense_management_service.dto.response;

import com.expense_management_service.enums.SplitType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseSplitResponse(
        UUID splitId,
        UUID lineItemId,
        UUID costCenterId,
        String costCenterName,
        SplitType splitType,
        BigDecimal percentage,
        BigDecimal allocatedAmount,
        Integer splitOrder,
        LocalDateTime createdAt
) {
}
