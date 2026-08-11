package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.service.DepartmentApproverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Admin-only CRUD for the department&rarr;approver mapping backing {@code ApproverSourceType.DEPARTMENT_OWNER}. */
@RestController
@RequestMapping("/xms/admin/department-approvers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DepartmentApproverController {

    private final DepartmentApproverService departmentApproverService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DepartmentApproverResponse> create(@Valid @RequestBody DepartmentApproverRequest request) {
        return ApiResponse.success("Department approver mapping created", departmentApproverService.create(request));
    }

    @PutMapping("/{departmentApproverId}")
    public ApiResponse<DepartmentApproverResponse> update(@PathVariable UUID departmentApproverId,
                                                           @Valid @RequestBody DepartmentApproverRequest request) {
        return ApiResponse.success("Department approver mapping updated", departmentApproverService.update(departmentApproverId, request));
    }

    @GetMapping("/{departmentApproverId}")
    public ApiResponse<DepartmentApproverResponse> getById(@PathVariable UUID departmentApproverId) {
        return ApiResponse.success(departmentApproverService.getById(departmentApproverId));
    }

    @GetMapping
    public ApiResponse<List<DepartmentApproverResponse>> getAll() {
        return ApiResponse.success(departmentApproverService.getAll());
    }

    @DeleteMapping("/{departmentApproverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID departmentApproverId) {
        departmentApproverService.delete(departmentApproverId);
    }
}
