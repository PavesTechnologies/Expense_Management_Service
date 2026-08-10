package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyAssignmentType;

import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyAssignmentResponse(
        UUID assignmentId,
        PolicyAssignmentType assignmentType,
        String employeeId,
        UUID groupId,
        String groupName,
        UUID policyId,
        String policyName,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
