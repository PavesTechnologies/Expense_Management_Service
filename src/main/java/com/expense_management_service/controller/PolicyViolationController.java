package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;
import com.expense_management_service.service.PolicyViolationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read and justify policy warnings on a single line item (EP05). Nested under the line item, which
 * is itself nested under the report — mirroring {@link ExpenseLineItemController}'s path shape —
 * so a violation can never be addressed independently of the item it was raised against.
 * Justifying annotates a warning; it never clears or blocks anything (see {@code PolicyEvaluator}).
 */
@RestController
@RequestMapping("/xms/employee/expense-reports/{reportId}/line-items/{lineItemId}/policy-warnings")
@RequiredArgsConstructor
public class PolicyViolationController {

    private final PolicyViolationService policyViolationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<List<PolicyWarningResponse>> getAll(@PathVariable UUID reportId, @PathVariable UUID lineItemId) {
        return ApiResponse.success(policyViolationService.getForLineItem(reportId, lineItemId));
    }

    @PostMapping("/{violationId}/justify")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<PolicyWarningResponse> justify(@PathVariable UUID reportId, @PathVariable UUID lineItemId,
                                                       @PathVariable UUID violationId,
                                                       @Valid @RequestBody PolicyJustificationRequest request) {
        return ApiResponse.success("Justification saved", policyViolationService.justify(reportId, lineItemId, violationId, request));
    }
}
