package com.expense_management_service.service;

import com.expense_management_service.dto.response.EmployeeSummaryResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.dto.response.TeamExpenseSummaryResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

/** The caller's team - their direct reports per EmployeeCache.managerEmployeeId. */
public interface TeamExpenseService {

    List<EmployeeSummaryResponse> getMembers();

    /** Team members' submitted reports; {@code employeeId}, when given, must be a team member. */
    PageResponse<ExpenseReportResponse> getReports(Pageable pageable, String status, String search, String employeeId);

    TeamExpenseSummaryResponse getSummary();
}
