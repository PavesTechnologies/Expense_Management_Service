package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.FinanceQueryRequest;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.FinanceLineItemReviewResponse;
import com.expense_management_service.dto.response.FinanceQueueItemResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.FinanceVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Finance Verification's own action surface - deliberately separate from {@code
 * ApprovalWorkflowController} (§Refactoring rule: no second workflow engine, but a distinct API
 * surface for a distinct action vocabulary).
 * <p>
 * Two-layer authorization, both required:
 * <ol>
 *   <li><b>Layer 1 (here, {@code @PreAuthorize}):</b> the caller must hold the {@code
 *   FINANCE_EXECUTIVE} role. Role-only, matching this module's existing convention of
 *   role-based (not fine-grained permission-based) endpoint authorization - this is a coarse
 *   "is this user even a Finance user" gate, it says nothing about which report.</li>
 *   <li><b>Layer 2 (unchanged, inside {@code FinanceVerificationServiceImpl}):</b> the caller
 *   must be the exact resolved Finance approver for THIS report, or their active delegate.
 *   Layer 1 passing never substitutes for this - a FINANCE_EXECUTIVE user with no assignment
 *   on a given report is still denied by Layer 2.</li>
 * </ol>
 * Deliberately does not include {@code ADMIN} in this check - add {@code
 * hasAnyRole('FINANCE_EXECUTIVE','ADMIN')} later if an admin override is actually wanted.
 */
@RestController
@RequestMapping("/xms/finance-verification")
@RequiredArgsConstructor
public class FinanceVerificationController {

    private static final String CAN_ACT_AS_FINANCE = "hasRole('FINANCE_EXECUTIVE')";

    private final FinanceVerificationService financeVerificationService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrentUserService currentUserService;

    @PostMapping("/{reportId}/line-items/{lineItemId}/verify")
    @PreAuthorize(CAN_ACT_AS_FINANCE)
    public ApiResponse<ExpenseReportResponse> verifyLineItem(@PathVariable UUID reportId, @PathVariable UUID lineItemId) {
        return ApiResponse.success("Line item verified",
                financeVerificationService.verifyLineItem(reportId, lineItemId, currentUserService.getEmployeeId()));
    }

    @PostMapping("/{reportId}/line-items/{lineItemId}/query")
    @PreAuthorize(CAN_ACT_AS_FINANCE)
    public ApiResponse<ExpenseReportResponse> queryLineItem(@PathVariable UUID reportId, @PathVariable UUID lineItemId,
                                                             @Valid @RequestBody FinanceQueryRequest request) {
        return ApiResponse.success("Finance query raised",
                financeVerificationService.queryLineItem(reportId, lineItemId, currentUserService.getEmployeeId(), request.reason()));
    }

    @GetMapping("/my-queue")
    @PreAuthorize(CAN_ACT_AS_FINANCE)
    public ApiResponse<PageResponse<FinanceQueueItemResponse>> getMyQueue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(financeVerificationService.getFinanceQueue(currentUserService.getEmployeeId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{reportId}/reviews")
    @PreAuthorize(CAN_ACT_AS_FINANCE)
    public ApiResponse<List<FinanceLineItemReviewResponse>> getFinanceReviews(@PathVariable UUID reportId) {
        return ApiResponse.success(financeVerificationService.getFinanceReviews(reportId, currentUserService.getEmployeeId()));
    }

    @GetMapping("/{reportId}/status")
    @PreAuthorize(CAN_ACT_AS_FINANCE)
    public ApiResponse<ApprovalStatusResponse> getStatus(@PathVariable UUID reportId) {
        return ApiResponse.success(approvalWorkflowService.getApprovalStatus(reportId));
    }
}
