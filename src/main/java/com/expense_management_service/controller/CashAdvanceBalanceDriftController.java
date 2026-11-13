package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.service.impl.CashAdvanceBalanceCalculator;
import com.expense_management_service.service.impl.CashAdvanceBalanceDriftService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only check that stored cash advance balances match their records. Finance and Admin only. */
@RestController
@RequestMapping("/xms/finance/cash-advance-balances")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
public class CashAdvanceBalanceDriftController {

    private final CashAdvanceBalanceDriftService driftService;

    @Operation(summary = "List advances whose stored outstanding balance differs from the balance their records support (read-only)")
    @GetMapping("/drift")
    public ApiResponse<List<CashAdvanceBalanceCalculator.Drift>> drift() {
        return ApiResponse.success(driftService.findDrift());
    }
}
