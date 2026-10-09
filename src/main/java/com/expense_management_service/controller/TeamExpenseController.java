package com.expense_management_service.controller;

import java.util.List;
import java.util.Set;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.EmployeeSummaryResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.dto.response.TeamExpenseSummaryResponse;
import com.expense_management_service.service.TeamExpenseService;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A manager's view of their direct reports' submitted expenses (Drafts excluded). */
@RestController
@RequestMapping("/xms/manager/team")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER','REPORTING_MANAGER','ADMIN')")
public class TeamExpenseController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "submittedAt", "totalAmount", "title", "reportStatus");

    private final TeamExpenseService teamExpenseService;

    @GetMapping("/members")
    public ApiResponse<List<EmployeeSummaryResponse>> getMembers() {
        return ApiResponse.success(teamExpenseService.getMembers());
    }

    @GetMapping("/expense-reports")
    public ApiResponse<PageResponse<ExpenseReportResponse>> getReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "submittedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDirection,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String employeeId) {
        Sort sort = Sort.by(Sort.Direction.fromString(sortDirection), SORTABLE.contains(sortBy) ? sortBy : "submittedAt");
        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(limit, 1), 100), sort);
        return ApiResponse.success(teamExpenseService.getReports(pageable, status, search, employeeId));
    }

    @GetMapping("/summary")
    public ApiResponse<TeamExpenseSummaryResponse> getSummary() {
        return ApiResponse.success(teamExpenseService.getSummary());
    }
}
