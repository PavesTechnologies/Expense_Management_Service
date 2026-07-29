package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.service.ApprovalTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/manager/approvals")
@RequiredArgsConstructor
public class ApprovalTaskController {

    private final ApprovalTaskService approvalTaskService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalTaskResponse> create(@Valid @RequestBody ApprovalTaskRequest request) {
        return ApiResponse.success("Approval task created", approvalTaskService.create(request));
    }

    @PutMapping("/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalTaskResponse> update(@PathVariable UUID taskId, @Valid @RequestBody ApprovalTaskRequest request) {
        return ApiResponse.success("Approval task updated", approvalTaskService.update(taskId, request));
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','GENERAL')")
    public ApiResponse<ApprovalTaskResponse> getById(@PathVariable UUID taskId) {
        return ApiResponse.success(approvalTaskService.getById(taskId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','GENERAL')")
    public ApiResponse<List<ApprovalTaskResponse>> getAll() {
        return ApiResponse.success(approvalTaskService.getAll());
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID taskId) {
        approvalTaskService.delete(taskId);
    }
}
