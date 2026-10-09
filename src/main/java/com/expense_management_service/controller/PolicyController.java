package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyRequest;
import com.expense_management_service.dto.response.PolicyResponse;
import com.expense_management_service.dto.response.PolicyVersionResponse;
import com.expense_management_service.service.PolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * CRUD for the {@code Policy} bundle itself. Deliberately not mapped to {@code /xms/admin/policies}
 * - that path already belongs to {@code PolicyRuleController}, where {@code {policyId}} means a
 * rule's own id (a historical naming collision predating the bundle model - see {@code
 * PolicyRule#getPolicy()}'s javadoc). {@code policyBundleId} is this API surface's established name
 * for the bundle, so this controller uses the matching {@code policy-bundles} path instead of
 * reusing an already-taken one.
 */
@RestController
@RequestMapping("/xms/admin/policy-bundles")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyResponse> create(@Valid @RequestBody PolicyRequest request) {
        return ApiResponse.success("Policy created", policyService.create(request));
    }

    @PutMapping("/{policyId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<PolicyResponse> update(@PathVariable UUID policyId, @Valid @RequestBody PolicyRequest request) {
        return ApiResponse.success("Policy updated", policyService.update(policyId, request));
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<PolicyResponse> getById(@PathVariable UUID policyId) {
        return ApiResponse.success(policyService.getById(policyId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyResponse>> getAll() {
        return ApiResponse.success(policyService.getAll());
    }

    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public void delete(@PathVariable UUID policyId) {
        policyService.delete(policyId);
    }

    @GetMapping("/{policyId}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyVersionResponse>> getVersionHistory(@PathVariable UUID policyId) {
        return ApiResponse.success(policyService.getVersionHistory(policyId));
    }
}
