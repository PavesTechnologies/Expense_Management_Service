package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record CostCenterResponse(
        UUID costCenterId,
        String costCenterCode,
        String costCenterName,
        UUID departmentUuid,
        String description,
        String ownerEmployeeId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Boolean allowUnbudgeted
) {

    public CostCenterResponse(UUID costCenterId, String costCenterCode, String costCenterName, UUID departmentUuid,
                              String description, String ownerEmployeeId, String status,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(costCenterId, costCenterCode, costCenterName, departmentUuid, description, ownerEmployeeId, status,
                createdAt, updatedAt, null);
    }
}
