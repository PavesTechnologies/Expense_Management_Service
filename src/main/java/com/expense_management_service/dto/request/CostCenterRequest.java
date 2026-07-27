package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CostCenterRequest(
        @NotBlank @Size(max = 255) String costCenterCode,
        @NotBlank @Size(max = 255) String costCenterName,
        @NotNull UUID departmentUuid,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 255) String ownerEmployeeId,
        @Size(max = 255) String status
) {
}
