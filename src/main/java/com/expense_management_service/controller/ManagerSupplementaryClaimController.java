package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SupplementaryClaimDecisionRequest;
import com.expense_management_service.dto.response.SupplementaryClaimResponse;
import com.expense_management_service.service.SupplementaryClaimService;
import com.expense_management_service.service.SupplementaryClaimService.Decision;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Manager review of supplementary claims. The service confirms the caller manages the employee on the claim. */
@RestController
@RequestMapping("/xms/manager/supplementary-claims")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','REPORTING_MANAGER')")
public class ManagerSupplementaryClaimController {

    private final SupplementaryClaimService service;

    @GetMapping("/queue")
    public ApiResponse<List<SupplementaryClaimResponse>> queue() {
        return ApiResponse.success(service.managerQueue());
    }

    @PostMapping("/{claimId}/approve")
    public ApiResponse<SupplementaryClaimResponse> approve(@PathVariable UUID claimId) {
        return ApiResponse.success("Approved. Sent to Finance.",
                service.managerReview(claimId, Decision.APPROVE, null));
    }

    @PostMapping("/{claimId}/reject")
    public ApiResponse<SupplementaryClaimResponse> reject(@PathVariable UUID claimId,
                                                          @RequestBody(required = false) SupplementaryClaimDecisionRequest request) {
        return ApiResponse.success("Rejected.",
                service.managerReview(claimId, Decision.REJECT, request == null ? null : request.comment()));
    }

    @PostMapping("/{claimId}/clarify")
    public ApiResponse<SupplementaryClaimResponse> clarify(@PathVariable UUID claimId,
                                                           @RequestBody(required = false) SupplementaryClaimDecisionRequest request) {
        return ApiResponse.success("Clarification requested.",
                service.managerReview(claimId, Decision.CLARIFY, request == null ? null : request.comment()));
    }
}
