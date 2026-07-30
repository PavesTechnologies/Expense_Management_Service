package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.ExpenseReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Create-and-save-Draft expense report endpoints (EP02-S1).
 * <p>
 * Ownership (an Employee may only touch their own report) and status-gating (edits only
 * while Draft/Policy Rejected/Query Raised, deletes only while Draft) are enforced inside
 * {@link ExpenseReportService}, not here — {@code @PreAuthorize} only gates coarse role
 * access; it cannot express "your own record".
 */
@RestController
@RequestMapping("/xms/employee/expense-reports")
@RequiredArgsConstructor
public class ExpenseReportController {

    private final ExpenseReportService expenseReportService;
    private final ApprovalWorkflowService approvalWorkflowService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseReportResponse> create(@Valid @RequestBody ExpenseReportRequest request) {
        return ApiResponse.success("Expense report created", expenseReportService.create(request));
    }

    @PutMapping("/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseReportResponse> update(@PathVariable UUID reportId,
                                                      @Valid @RequestBody ExpenseReportRequest request) {
        return ApiResponse.success("Expense report updated", expenseReportService.update(reportId, request));
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<ExpenseReportResponse> getById(@PathVariable UUID reportId) {
        return ApiResponse.success(expenseReportService.getById(reportId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER')")
    public ApiResponse<List<ExpenseReportResponse>> getAll() {
        return ApiResponse.success(expenseReportService.getAll());
    }

    @DeleteMapping("/{reportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public void delete(@PathVariable UUID reportId) {
        expenseReportService.delete(reportId);
    }

    @PostMapping("/{reportId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<ExpenseReportResponse> submit(@PathVariable UUID reportId) {
        return ApiResponse.success("Expense report submitted for approval", approvalWorkflowService.submit(reportId));
    }
}
