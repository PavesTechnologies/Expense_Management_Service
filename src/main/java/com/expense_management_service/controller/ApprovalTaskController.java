package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalActionRequest;
import com.expense_management_service.dto.request.ApprovalTaskRequest;
import com.expense_management_service.dto.response.ApprovalTaskResponse;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalTaskService;
import com.expense_management_service.service.ApprovalWorkflowService;
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
    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrentUserService currentUserService;

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
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','EMPLOYEE')")
    public ApiResponse<ApprovalTaskResponse> getById(@PathVariable UUID taskId) {
        return ApiResponse.success(approvalTaskService.getById(taskId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','EMPLOYEE')")
    public ApiResponse<List<ApprovalTaskResponse>> getAll() {
        return ApiResponse.success(approvalTaskService.getAll());
    }

    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID taskId) {
        approvalTaskService.delete(taskId);
    }

    @PostMapping("/{taskId}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalTaskResponse> approve(@PathVariable UUID taskId,
                                                      @RequestBody(required = false) ApprovalActionRequest request) {
        String comments = request != null ? request.comments() : null;
        return ApiResponse.success("Approval task approved",
                approvalWorkflowService.approve(taskId, currentUserService.getEmployeeId(), comments));
    }

    @PostMapping("/{taskId}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalTaskResponse> reject(@PathVariable UUID taskId,
                                                     @RequestBody(required = false) ApprovalActionRequest request) {
        String comments = request != null ? request.comments() : null;
        return ApiResponse.success("Approval task rejected",
                approvalWorkflowService.reject(taskId, currentUserService.getEmployeeId(), comments));
    }

    @GetMapping("/my-queue")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','EMPLOYEE')")
    public ApiResponse<List<ApprovalTaskResponse>> myQueue() {
        return ApiResponse.success(approvalWorkflowService.getMyQueue(currentUserService.getEmployeeId()));
    }

    /** EP05: full policy-warning drill-down (with any employee justification) for the task's report. */
    @GetMapping("/{taskId}/policy-warnings")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<List<PolicyWarningResponse>> getPolicyWarnings(@PathVariable UUID taskId) {
        return ApiResponse.success(approvalWorkflowService.getPolicyWarningsForTask(taskId));
    }
}
