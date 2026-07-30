package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record ApprovalMatrixResponse(
        UUID matrixId,
        UUID costCenterId,
        String costCenterName,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        Integer approvalLevel,
        String approverType,
        String approverReference,
        String status,
        String approvalMode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
