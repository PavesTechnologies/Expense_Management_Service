package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DepartmentApproverRequest(
        @NotNull UUID departmentUuid,
        @NotBlank @Size(max = 255) String approverEmployeeId,
        @Size(max = 255) String status
) {
}
