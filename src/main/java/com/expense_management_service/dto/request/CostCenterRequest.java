package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code allowUnbudgeted} is optional: null on create defaults to {@code false}, and null on update
 * leaves the stored flag untouched, so callers that predate the field never silently reset it.
 */
public record CostCenterRequest(
        @NotBlank @Size(max = 255) String costCenterCode,
        @NotBlank @Size(max = 255) String costCenterName,
        @NotNull UUID departmentUuid,
        @Size(max = 1000) String description,
        /** EOS {@code employeeId} of the owning employee - validated against EmployeeCache, not UMS. */
        @NotBlank @Size(max = 255) String ownerEmployeeId,
        @Size(max = 255) String status,
        Boolean allowUnbudgeted
) {
    /** Backward-compatible overload for every call site written before {@code allowUnbudgeted} was exposed - defaults it to "not supplied". */
    public CostCenterRequest(String costCenterCode, String costCenterName, UUID departmentUuid, String description,
                             String ownerEmployeeId, String status) {
        this(costCenterCode, costCenterName, departmentUuid, description, ownerEmployeeId, status, null);
    }
}
