package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ApplyAdvanceOffsetRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.service.impl.CashAdvanceOffsetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Finance applies a verified expense report against the cash advance it settles.
 * Matches the call the Finance Review panel makes: {@code cashAdvanceApi.applyAdvanceOffset}.
 */
@RestController
@RequestMapping("/xms/api/v1/expense-reports")
@RequiredArgsConstructor
public class ExpenseReportOffsetController {

    private final CashAdvanceOffsetService cashAdvanceOffsetService;

    @PostMapping("/{reportId}/apply-advance-offset")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<CashAdvanceAdjustmentResponse> applyAdvanceOffset(
            @PathVariable UUID reportId,
            @RequestBody(required = false) ApplyAdvanceOffsetRequest request) {

        UUID advanceId = request != null ? request.cashAdvanceId() : null;
        java.math.BigDecimal amount = request != null ? request.offsetAmount() : null;

        return ApiResponse.success("Advance offset applied",
                cashAdvanceOffsetService.applyOffset(reportId, advanceId, amount));
    }
}
