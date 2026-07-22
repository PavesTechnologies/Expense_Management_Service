package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.service.ApprovalTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-tasks")
@RequiredArgsConstructor
public class ApprovalTaskController {

    private final ApprovalTaskService approvalTaskService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalTaskResponse> create(@Valid @RequestBody ApprovalTaskRequest request) {
        return ApiResponse.success("Approval task created", approvalTaskService.create(request));
    }

    @PutMapping("/{taskId}")
    public ApiResponse<ApprovalTaskResponse> update(@PathVariable UUID taskId, @Valid @RequestBody ApprovalTaskRequest request) {
        return ApiResponse.success("Approval task updated", approvalTaskService.update(taskId, request));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ApprovalTaskResponse> getById(@PathVariable UUID taskId) {
        return ApiResponse.success(approvalTaskService.getById(taskId));
    }

    @GetMapping
    public ApiResponse<Page<ApprovalTaskResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(approvalTaskService.getAll(pageable));
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID taskId) {
        approvalTaskService.delete(taskId);
    }
}
