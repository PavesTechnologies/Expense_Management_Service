package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record ApprovalTaskResponse(
        UUID taskId,
        UUID reportId,
        String reportNumber,
        String approverId,
        Integer approvalLevel,
        String taskStatus,
        String comments,
        LocalDateTime assignedAt,
        LocalDateTime actionedAt,
        LocalDateTime dueDate,
        UUID groupId,
        Integer submissionCycle,
        String actedBy,
        String approvalMode
) {
}
