package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PolicyAssignmentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code assignmentType} must be {@code INDIVIDUAL} or {@code GROUP} - the single system-wide
 * {@code DEFAULT} assignment is seeded once and repointed via a dedicated endpoint, never created
 * through this one. {@code employeeId} is required (and {@code groupId} ignored) for {@code
 * INDIVIDUAL}; {@code groupId} is required (and {@code employeeId} ignored) for {@code GROUP}.
 */
public record PolicyAssignmentRequest(
        @NotNull PolicyAssignmentType assignmentType,
        @Size(max = 255) String employeeId,
        UUID groupId,
        @NotNull UUID policyId,
        @Size(max = 255) String status
) {
}
