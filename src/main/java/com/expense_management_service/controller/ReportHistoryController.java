package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.ReportHistoryEntryResponse;
import com.expense_management_service.service.ReportHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A report's history across correction cycles - who did what, and the reasons given. */
@RestController
@RequestMapping("/xms/employee/expense-reports")
@RequiredArgsConstructor
public class ReportHistoryController {

    private final ReportHistoryService reportHistoryService;

    @GetMapping("/{reportId}/history")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<List<ReportHistoryEntryResponse>> getHistory(@PathVariable UUID reportId) {
        return ApiResponse.success(reportHistoryService.getHistory(reportId));
    }
}
