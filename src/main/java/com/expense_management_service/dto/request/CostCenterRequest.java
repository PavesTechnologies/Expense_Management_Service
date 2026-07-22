package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CostCenterRequest(
        @NotBlank @Size(max = 255) String costCenterCode,
        @NotBlank @Size(max = 255) String costCenterName,
        UUID parentCostCenterId,
        @Size(max = 255) String ownerEmployeeId,
        @Size(max = 255) String status
) {
}
