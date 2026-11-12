package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.AssignedProjectResponse;
import com.expense_management_service.service.EmployeeProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Project selection for client-billable expense submission (Epic 8).
 * Distinct from the admin-only CRUD in {@code ProjectCacheController} — this is the
 * employee-facing, PMS-backed read path.
 */
@RestController
@RequestMapping("/xms/employee/projects")
@RequiredArgsConstructor
public class EmployeeProjectController {

    private final EmployeeProjectService employeeProjectService;

    @GetMapping("/assigned")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<List<AssignedProjectResponse>> getAssignedProjects() {
        return ApiResponse.success(employeeProjectService.getAssignedProjects());
    }
}
