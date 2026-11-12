package com.expense_management_service.service;

import com.expense_management_service.dto.response.EmployeeSummaryResponse;

import java.util.List;

public interface EmployeeDirectoryService {

    /** Every currently Active employee from the EmployeeCache, sorted by name. */
    List<EmployeeSummaryResponse> getActiveEmployees();
}
