package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import com.expense_management_service.service.ApprovalDelegationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/manager/approval-delegations")
@RequiredArgsConstructor
public class ApprovalDelegationController {

    private final ApprovalDelegationService approvalDelegationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalDelegationResponse> create(@Valid @RequestBody ApprovalDelegationRequest request) {
        return ApiResponse.success("Approval delegation created", approvalDelegationService.create(request));
    }

    @PutMapping("/{delegationId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE')")
    public ApiResponse<ApprovalDelegationResponse> update(@PathVariable UUID delegationId,
                                                           @Valid @RequestBody ApprovalDelegationRequest request) {
        return ApiResponse.success("Approval delegation updated", approvalDelegationService.update(delegationId, request));
    }

    @GetMapping("/{delegationId}")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','GENERAL')")
    public ApiResponse<ApprovalDelegationResponse> getById(@PathVariable UUID delegationId) {
        return ApiResponse.success(approvalDelegationService.getById(delegationId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER','FINANCE','GENERAL')")
    public ApiResponse<List<ApprovalDelegationResponse>> getAll() {
        return ApiResponse.success(approvalDelegationService.getAll());
    }

    @DeleteMapping("/{delegationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID delegationId) {
        approvalDelegationService.delete(delegationId);
    }
}
