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
    private final com.expense_management_service.service.impl.EmployeeMoneyReportService employeeMoneyReportService;

    @GetMapping("/xms/reports/expense-summary")
    @PreAuthorize("hasAnyRole('MANAGER','REPORTING_MANAGER','FINANCE','FINANCE_EXECUTIVE','ADMIN','SUPER_ADMIN')")
    public ApiResponse<ExpenseSummaryReportResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(expenseReportingService.summary(from, to));
    }

    /**
     * Finance's end-to-end money view: per employee, per department and in total, every cash advance and expense claim by
     * where its money is now, the tax on the claims, and the fiscal year's budget left. Organization-wide, so Finance and
     * Admin only. Dates are optional (advances by request date, reports by submit date).
     */
    @GetMapping("/xms/reports/employee-money")
    @PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE','ADMIN','SUPER_ADMIN')")
    public ApiResponse<com.expense_management_service.dto.response.EmployeeMoneyReportResponse> employeeMoney(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(employeeMoneyReportService.report(from, to));
    }
}
