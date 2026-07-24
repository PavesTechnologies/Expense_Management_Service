package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;


import java.util.UUID;

public interface ApprovalTaskService {

    ApprovalTaskResponse create(ApprovalTaskRequest request);

    ApprovalTaskResponse update(UUID taskId, ApprovalTaskRequest request);

    ApprovalTaskResponse getById(UUID taskId);

    List<ApprovalTaskResponse> getAll();

    void delete(UUID taskId);
}
