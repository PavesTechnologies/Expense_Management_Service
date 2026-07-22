package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record CostCenterResponse(
        UUID costCenterId,
        String costCenterCode,
        String costCenterName,
        UUID parentCostCenterId,
        String parentCostCenterName,
        String ownerEmployeeId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
