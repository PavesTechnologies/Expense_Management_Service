package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.AllocationContextResponse;
import com.expense_management_service.service.CrossDepartmentAllocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the expense form tell when a chosen cost center is outside the caller's home department. */
@RestController
@RequestMapping("/xms/employee/allocation-context")
@RequiredArgsConstructor
public class AllocationContextController {

    private final CrossDepartmentAllocationService crossDepartmentAllocationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<AllocationContextResponse> getAllocationContext() {
        return ApiResponse.success(crossDepartmentAllocationService.getAllocationContextForCurrentUser());
    }
}
