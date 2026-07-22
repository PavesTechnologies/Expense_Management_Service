package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ApprovalTaskService {

    ApprovalTaskResponse create(ApprovalTaskRequest request);

    ApprovalTaskResponse update(UUID taskId, ApprovalTaskRequest request);

    ApprovalTaskResponse getById(UUID taskId);

    Page<ApprovalTaskResponse> getAll(Pageable pageable);

    void delete(UUID taskId);
}
