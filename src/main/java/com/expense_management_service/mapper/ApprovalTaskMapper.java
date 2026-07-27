package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.entity.ApprovalTask;
import com.expense_management_service.enums.TaskStatus;
import org.springframework.stereotype.Component;

@Component
public class ApprovalTaskMapper {

    public ApprovalTask toEntity(ApprovalTaskRequest request) {
        return ApprovalTask.builder()
                .approverId(request.approverId())
                .approvalLevel(request.approvalLevel())
                .taskStatus(toTaskStatus(request.taskStatus()))
                .comments(request.comments())
                .dueDate(request.dueDate())
                .build();
    }

    public void updateEntity(ApprovalTask entity, ApprovalTaskRequest request) {
        entity.setApproverId(request.approverId());
        entity.setApprovalLevel(request.approvalLevel());
        entity.setTaskStatus(toTaskStatus(request.taskStatus()));
        entity.setComments(request.comments());
        entity.setDueDate(request.dueDate());
    }

    public ApprovalTaskResponse toResponse(ApprovalTask entity) {
        return new ApprovalTaskResponse(
                entity.getTaskId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getReport() != null ? entity.getReport().getReportNumber() : null,
                entity.getApproverId(),
                entity.getApprovalLevel(),
                entity.getTaskStatus() != null ? entity.getTaskStatus().name() : null,
                entity.getComments(),
                entity.getAssignedAt(),
                entity.getActionedAt(),
                entity.getDueDate(),
                entity.getGroupId(),
                entity.getSubmissionCycle(),
                entity.getActedBy(),
                entity.getApprovalMode() != null ? entity.getApprovalMode().name() : null
        );
    }

    private TaskStatus toTaskStatus(String taskStatus) {
        return taskStatus != null ? TaskStatus.valueOf(taskStatus) : null;
    }
}
