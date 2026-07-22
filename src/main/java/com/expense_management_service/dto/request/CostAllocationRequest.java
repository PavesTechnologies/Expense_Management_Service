package com.expense_management_service.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CostAllocationRequest(
        @NotNull UUID lineItemId,
        @NotNull UUID costCenterId,
        @DecimalMin(value = "0", inclusive = true) @DecimalMax(value = "100", inclusive = true) BigDecimal allocationPercentage,
        BigDecimal allocationAmount
) {
}
