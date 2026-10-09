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

/**
 * Create/update/delete are intentionally open to any authenticated employee at the URL-permission
 * level - under this engine's design (§1.5) any employee, regardless of role, can be a resolved
 * approver (Named User / Department Owner / Cost-Center Owner), so a role gate here would block
 * exactly the people who most need to self-set a delegate. The real authorization is an ownership
 * check inside {@code ApprovalDelegationServiceImpl}: a non-ADMIN caller may only create/update/
 * delete a delegation where they are the delegator themselves; ADMIN may act on anyone's.
 */
@RestController
@RequestMapping("/xms/manager/approval-delegations")
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
    public void delete(@PathVariable UUID delegationId) {
        approvalDelegationService.delete(delegationId);
    }
}
