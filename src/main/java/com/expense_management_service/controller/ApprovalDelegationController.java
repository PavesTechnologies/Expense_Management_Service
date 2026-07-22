package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import com.expense_management_service.service.ApprovalDelegationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-delegations")
@RequiredArgsConstructor
public class ApprovalDelegationController {

    private final ApprovalDelegationService approvalDelegationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ApprovalDelegationResponse> create(@Valid @RequestBody ApprovalDelegationRequest request) {
        return ApiResponse.success("Approval delegation created", approvalDelegationService.create(request));
    }

    @PutMapping("/{delegationId}")
    public ApiResponse<ApprovalDelegationResponse> update(@PathVariable UUID delegationId,
                                                           @Valid @RequestBody ApprovalDelegationRequest request) {
        return ApiResponse.success("Approval delegation updated", approvalDelegationService.update(delegationId, request));
    }

    @GetMapping("/{delegationId}")
    public ApiResponse<ApprovalDelegationResponse> getById(@PathVariable UUID delegationId) {
        return ApiResponse.success(approvalDelegationService.getById(delegationId));
    }

    @GetMapping
    public ApiResponse<Page<ApprovalDelegationResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(approvalDelegationService.getAll(pageable));
    }

    @DeleteMapping("/{delegationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID delegationId) {
        approvalDelegationService.delete(delegationId);
    }
}
