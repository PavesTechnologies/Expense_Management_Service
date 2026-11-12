package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record DepartmentApproverResponse(
        UUID departmentApproverId,
        UUID departmentUuid,
        String approverEmployeeId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
