package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.service.PolicyRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/xms/admin/policies")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleService policyRuleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PolicyRuleResponse> create(@Valid @RequestBody PolicyRuleRequest request) {
        return ApiResponse.success("Policy rule created", policyRuleService.create(request));
    }

    @PutMapping("/{policyId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PolicyRuleResponse> update(@PathVariable UUID policyId, @Valid @RequestBody PolicyRuleRequest request) {
        return ApiResponse.success("Policy rule updated", policyRuleService.update(policyId, request));
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<PolicyRuleResponse> getById(@PathVariable UUID policyId) {
        return ApiResponse.success(policyRuleService.getById(policyId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyRuleResponse>> getAll() {
        return ApiResponse.success(policyRuleService.getAll());
    }

    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID policyId) {
        policyRuleService.delete(policyId);
    }
}
