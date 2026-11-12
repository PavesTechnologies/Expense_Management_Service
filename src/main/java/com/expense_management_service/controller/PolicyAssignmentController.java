package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyAssignmentRequest;
import com.expense_management_service.dto.response.PolicyAssignmentResponse;
import com.expense_management_service.service.PolicyAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/xms/admin/policy-assignments")
@RequiredArgsConstructor
public class PolicyAssignmentController {

    private final PolicyAssignmentService policyAssignmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyAssignmentResponse> create(@Valid @RequestBody PolicyAssignmentRequest request) {
        return ApiResponse.success("Policy assignment created", policyAssignmentService.create(request));
    }

    @GetMapping("/{assignmentId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<PolicyAssignmentResponse> getById(@PathVariable UUID assignmentId) {
        return ApiResponse.success(policyAssignmentService.getById(assignmentId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyAssignmentResponse>> getAll() {
        return ApiResponse.success(policyAssignmentService.getAll());
    }

    @DeleteMapping("/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public void delete(@PathVariable UUID assignmentId) {
        policyAssignmentService.delete(assignmentId);
    }

    @PutMapping("/default/{policyId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyAssignmentResponse> updateDefaultPolicy(@PathVariable UUID policyId) {
        return ApiResponse.success("Default policy updated", policyAssignmentService.updateDefaultPolicy(policyId));
    }
}
