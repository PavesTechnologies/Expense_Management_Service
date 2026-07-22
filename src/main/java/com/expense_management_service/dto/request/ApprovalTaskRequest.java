package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public record ApprovalTaskRequest(
        @NotNull UUID reportId,
        @NotBlank @Size(max = 255) String approverId,
        Integer approvalLevel,
        @Size(max = 255) String taskStatus,
        String comments,
        LocalDateTime dueDate
) {
}
