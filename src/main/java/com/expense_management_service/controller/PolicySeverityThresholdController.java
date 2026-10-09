package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicySeverityThresholdRequest;
import com.expense_management_service.dto.response.PolicySeverityThresholdResponse;
import com.expense_management_service.service.PolicySeverityThresholdService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** {@code policyId} query param omitted targets the global default severity-tier band set. */
@RestController
@RequestMapping("/xms/admin/severity-thresholds")
@RequiredArgsConstructor
public class PolicySeverityThresholdController {

    private final PolicySeverityThresholdService policySeverityThresholdService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','MANAGER')")
    public ApiResponse<List<PolicySeverityThresholdResponse>> getForScope(@RequestParam(required = false) UUID policyId) {
        return ApiResponse.success(policySeverityThresholdService.getForScope(policyId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    public ApiResponse<List<PolicySeverityThresholdResponse>> replaceForScope(
            @RequestParam(required = false) UUID policyId,
            @Valid @RequestBody List<PolicySeverityThresholdRequest> requests) {
        return ApiResponse.success("Severity threshold bands updated", policySeverityThresholdService.replaceForScope(policyId, requests));
    }
}
