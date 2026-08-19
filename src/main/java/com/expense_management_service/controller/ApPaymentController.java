package com.expense_management_service.controller;

import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ApPaymentDetailsResponse;
import com.expense_management_service.dto.response.ApPaymentQueueItemResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApPaymentService;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AP_EXECUTIVE's own action surface - role-only authorization ({@code hasRole('AP_EXECUTIVE')}),
 * matching this module's existing role-based (not fine-grained permission-based) convention (see
 * {@code FinanceVerificationController}). Deliberately excludes {@code ADMIN} - add {@code
 * hasAnyRole('AP_EXECUTIVE','ADMIN')} later if an admin override is actually wanted.
 */
@RestController
@RequestMapping("/xms/ap-payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AP_EXECUTIVE')")
public class ApPaymentController {

    private final ApPaymentService apPaymentService;
    private final CurrentUserService currentUserService;

    @GetMapping("/queue")
    public ApiResponse<PageResponse<ApPaymentQueueItemResponse>> getApQueue(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(apPaymentService.getApQueue(PageRequest.of(page, size)));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<ApPaymentDetailsResponse> getPaymentDetails(@PathVariable UUID reportId) {
        return ApiResponse.success(apPaymentService.getPaymentDetails(reportId));
    }

    @PostMapping("/{reportId}/complete")
    public ApiResponse<ExpenseReportResponse> markPaymentCompleted(@PathVariable UUID reportId) {
        return ApiResponse.success("Payment marked as completed",
                apPaymentService.markPaymentCompleted(reportId, currentUserService.getEmployeeId()));
    }
}
