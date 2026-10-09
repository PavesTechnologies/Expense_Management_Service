package com.expense_management_service.controller;

import java.time.LocalDate;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ExpenseSummaryReportResponse;
import com.expense_management_service.service.ExpenseReportingService;
import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cross-functional expense reporting - organization-wide for Finance/Admin, team-scoped for managers. */
@RestController
@RequiredArgsConstructor
public class ExpenseReportingController {

    private final ExpenseReportingService expenseReportingService;

    @GetMapping("/xms/reports/expense-summary")
    @PreAuthorize("hasAnyRole('MANAGER','REPORTING_MANAGER','FINANCE','FINANCE_EXECUTIVE','ADMIN','SUPER_ADMIN')")
    public ApiResponse<ExpenseSummaryReportResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(expenseReportingService.summary(from, to));
    }
}
