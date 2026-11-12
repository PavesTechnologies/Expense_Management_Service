package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyGroupMemberRequest;
import com.expense_management_service.dto.request.PolicyGroupRequest;
import com.expense_management_service.dto.response.PolicyGroupMemberResponse;
import com.expense_management_service.dto.response.PolicyGroupResponse;
import com.expense_management_service.service.PolicyGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/xms/admin/policy-groups")
@RequiredArgsConstructor
public class PolicyGroupController {

    private final PolicyGroupService policyGroupService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyGroupResponse> create(@Valid @RequestBody PolicyGroupRequest request) {
        return ApiResponse.success("Policy group created", policyGroupService.create(request));
    }

    @PutMapping("/{groupId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyGroupResponse> update(@PathVariable UUID groupId, @Valid @RequestBody PolicyGroupRequest request) {
        return ApiResponse.success("Policy group updated", policyGroupService.update(groupId, request));
    }

    @GetMapping("/{groupId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<PolicyGroupResponse> getById(@PathVariable UUID groupId) {
        return ApiResponse.success(policyGroupService.getById(groupId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyGroupResponse>> getAll() {
        return ApiResponse.success(policyGroupService.getAll());
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public void delete(@PathVariable UUID groupId) {
        policyGroupService.delete(groupId);
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyGroupMemberResponse> addMember(@PathVariable UUID groupId, @Valid @RequestBody PolicyGroupMemberRequest request) {
        return ApiResponse.success("Employee added to policy group", policyGroupService.addMember(groupId, request));
    }

    @DeleteMapping("/{groupId}/members/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public void removeMember(@PathVariable UUID groupId, @PathVariable String employeeId) {
        policyGroupService.removeMember(groupId, employeeId);
    }

    @GetMapping("/{groupId}/members")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyGroupMemberResponse>> getMembers(@PathVariable UUID groupId) {
        return ApiResponse.success(policyGroupService.getMembers(groupId));
    }
}
