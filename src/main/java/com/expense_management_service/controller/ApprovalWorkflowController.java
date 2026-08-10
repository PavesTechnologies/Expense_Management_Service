package com.expense_management_service.controller;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.dto.request.LineItemReviewRequest;
import com.expense_management_service.dto.request.RejectReportRequest;
import com.expense_management_service.dto.response.ApprovalQueueItemResponse;
import com.expense_management_service.dto.response.ApprovalStatusResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.LineItemReviewResponse;
import com.expense_management_service.service.ApprovalWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/**
 * Replaces EP06's {@code ApprovalTaskController}. Action endpoints (recall/cancel/review/reject) are
 * intentionally open to any authenticated employee at the URL-permission level (§1.5) - the real
 * authorization is the per-task "are you the exact resolved approver or their active delegate" check
 * enforced inside {@code ApprovalWorkflowServiceImpl}, not a role gate. "My Approvals" is
 * presence-based (§9.1) and needs no role restriction either.
 */
@RestController
@RequestMapping("/xms/approvals")
@RequiredArgsConstructor
public class ApprovalWorkflowController {

    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrentUserService currentUserService;

    @PostMapping("/{reportId}/submit")
    public ApiResponse<ExpenseReportResponse> submit(@PathVariable UUID reportId) {
        return ApiResponse.success("Report submitted for approval", approvalWorkflowService.submit(reportId));
    }

    @PostMapping("/{reportId}/recall")
    public ApiResponse<ExpenseReportResponse> recall(@PathVariable UUID reportId) {
        return ApiResponse.success("Report recalled to Draft", approvalWorkflowService.recall(reportId, currentUserService.getEmployeeId()));
    }

    @PostMapping("/{reportId}/cancel")
    public ApiResponse<ExpenseReportResponse> cancel(@PathVariable UUID reportId) {
        return ApiResponse.success("Report cancelled", approvalWorkflowService.cancel(reportId, currentUserService.getEmployeeId()));
    }

    @PostMapping("/{reportId}/line-items/{lineItemId}/review")
    public ApiResponse<ExpenseReportResponse> reviewLineItem(@PathVariable UUID reportId, @PathVariable UUID lineItemId,
                                                              @Valid @RequestBody LineItemReviewRequest request) {
        return ApiResponse.success("Line item reviewed",
                approvalWorkflowService.reviewLineItem(reportId, lineItemId, currentUserService.getEmployeeId(), request));
    }

    @PostMapping("/{reportId}/reject")
    public ApiResponse<ExpenseReportResponse> reject(@PathVariable UUID reportId, @Valid @RequestBody RejectReportRequest request) {
        return ApiResponse.success("Report rejected",
                approvalWorkflowService.rejectReport(reportId, currentUserService.getEmployeeId(), request));
    }

    @PostMapping("/{reportId}/bulk-approve")
    public ApiResponse<ExpenseReportResponse> bulkApprove(@PathVariable UUID reportId) {
        return ApiResponse.success("Report bulk-approved", approvalWorkflowService.bulkApprove(reportId, currentUserService.getEmployeeId()));
    }

    @GetMapping("/my-queue")
    public ApiResponse<List<ApprovalQueueItemResponse>> getMyQueue() {
        return ApiResponse.success(approvalWorkflowService.getMyQueue(currentUserService.getEmployeeId()));
    }

    @GetMapping("/{reportId}/line-item-reviews")
    public ApiResponse<List<LineItemReviewResponse>> getLineItemReviews(@PathVariable UUID reportId) {
        return ApiResponse.success(approvalWorkflowService.getLineItemReviews(reportId, currentUserService.getEmployeeId()));
    }

    @GetMapping("/{reportId}/status")
    public ApiResponse<ApprovalStatusResponse> getApprovalStatus(@PathVariable UUID reportId) {
        return ApiResponse.success(approvalWorkflowService.getApprovalStatus(reportId));
    }

    @GetMapping("/my-history")
    public ApiResponse<List<ExpenseReportResponse>> getMyHistory(@RequestParam(required = false) String outcome) {
        return ApiResponse.success(approvalWorkflowService.getMyHistory(currentUserService.getEmployeeId(), outcome));
    }
}
