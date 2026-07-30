package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record ApprovalMatrixRequest(
        @NotNull UUID costCenterId,
        @PositiveOrZero BigDecimal minimumAmount,
        @PositiveOrZero BigDecimal maximumAmount,
        Integer approvalLevel,
        @Size(max = 255) String approverType,
        @Size(max = 255) String approverReference,
        @Size(max = 255) String status,
        @Size(max = 255) String approvalMode
) {
}
