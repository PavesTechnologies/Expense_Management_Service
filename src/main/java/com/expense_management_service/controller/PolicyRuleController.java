package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.service.PolicyRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policy-rules")
@RequiredArgsConstructor
public class PolicyRuleController {

    private final PolicyRuleService policyRuleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PolicyRuleResponse> create(@Valid @RequestBody PolicyRuleRequest request) {
        return ApiResponse.success("Policy rule created", policyRuleService.create(request));
    }

    @PutMapping("/{policyId}")
    public ApiResponse<PolicyRuleResponse> update(@PathVariable UUID policyId, @Valid @RequestBody PolicyRuleRequest request) {
        return ApiResponse.success("Policy rule updated", policyRuleService.update(policyId, request));
    }

    @GetMapping("/{policyId}")
    public ApiResponse<PolicyRuleResponse> getById(@PathVariable UUID policyId) {
        return ApiResponse.success(policyRuleService.getById(policyId));
    }

    @GetMapping
    public ApiResponse<Page<PolicyRuleResponse>> getAll(Pageable pageable) {
        return ApiResponse.success(policyRuleService.getAll(pageable));
    }

    @DeleteMapping("/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID policyId) {
        policyRuleService.delete(policyId);
    }
}
