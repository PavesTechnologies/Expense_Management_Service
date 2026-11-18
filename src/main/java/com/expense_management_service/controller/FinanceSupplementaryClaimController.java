package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SupplementaryClaimCreateRequest;
import com.expense_management_service.dto.request.SupplementaryClaimDecisionRequest;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.service.SupplementaryClaimService;
import com.expense_management_service.service.SupplementaryClaimService.Decision;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Finance side of supplementary claims. Both the role and the permission are required; a missing permission fails closed
 * in the service.
 */
@RestController
@RequestMapping("/xms/finance/supplementary-claims")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE')")
public class FinanceSupplementaryClaimController {

    private final SupplementaryClaimService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SUPP_CLAIM_CREATE_FOR_EMPLOYEE')")
    public ApiResponse<SupplementaryClaimResponse> createOnBehalf(@Valid @RequestBody SupplementaryClaimCreateRequest request) {
        return ApiResponse.success("Claim created. The employee must acknowledge it before it is submitted.",
                service.createOnBehalf(request));
    }

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE')")
    public ApiResponse<List<SupplementaryClaimResponse>> queue() {
        return ApiResponse.success(service.financeQueue());
    }

    @PostMapping("/{claimId}/approve")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE')")
    public ApiResponse<SupplementaryClaimResponse> approve(@PathVariable UUID claimId) {
        return ApiResponse.success("Approved. Sent for AP payment.",
                service.financeReview(claimId, Decision.APPROVE, null));
    }

    @PostMapping("/{claimId}/reject")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE')")
    public ApiResponse<SupplementaryClaimResponse> reject(@PathVariable UUID claimId,
                                                          @RequestBody(required = false) SupplementaryClaimDecisionRequest request) {
        return ApiResponse.success("Rejected.",
                service.financeReview(claimId, Decision.REJECT, request == null ? null : request.comment()));
    }

    @PostMapping("/{claimId}/clarify")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE')")
    public ApiResponse<SupplementaryClaimResponse> clarify(@PathVariable UUID claimId,
                                                           @RequestBody(required = false) SupplementaryClaimDecisionRequest request) {
        return ApiResponse.success("Clarification requested.",
                service.financeReview(claimId, Decision.CLARIFY, request == null ? null : request.comment()));
    }
}
