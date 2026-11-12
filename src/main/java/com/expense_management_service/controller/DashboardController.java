package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role-based dashboards. Each endpoint is restricted to the role whose workflow it describes; a user
 * with several roles (e.g. GENERAL + MANAGER) calls each one they hold.
 */
@RestController
@RequestMapping("/xms/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /** Everyone has their own expenses. */
    @GetMapping("/employee")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DashboardResponse> employee() {
        return ApiResponse.success(dashboardService.employee());
    }

    /** Approvals assigned to the caller - managers and reporting managers alike. */
    @GetMapping("/manager")
    @PreAuthorize("hasAnyRole('MANAGER','REPORTING_MANAGER')")
    public ApiResponse<DashboardResponse> manager() {
        return ApiResponse.success(dashboardService.manager());
    }

    @GetMapping("/finance")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE')")
    public ApiResponse<DashboardResponse> finance() {
        return ApiResponse.success(dashboardService.finance());
    }

    @GetMapping("/ap")
    @PreAuthorize("hasRole('AP_EXECUTIVE')")
    public ApiResponse<DashboardResponse> ap() {
        return ApiResponse.success(dashboardService.ap());
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<DashboardResponse> admin() {
        return ApiResponse.success(dashboardService.admin());
    }
}
