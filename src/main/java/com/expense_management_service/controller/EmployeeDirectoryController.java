package com.expense_management_service.controller;

import java.util.List;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.EmployeeSummaryResponse;
import com.expense_management_service.service.EmployeeDirectoryService;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Active employees for pickers - open to every signed-in role, since any employee can pick their own delegate. */
@RestController
@RequestMapping("/xms/employees")
@RequiredArgsConstructor
public class EmployeeDirectoryController {

    private final EmployeeDirectoryService employeeDirectoryService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<EmployeeSummaryResponse>> getActiveEmployees() {
        return ApiResponse.success(employeeDirectoryService.getActiveEmployees());
    }
}
