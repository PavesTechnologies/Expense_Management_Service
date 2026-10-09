package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.TaxReportResponse;
import com.expense_management_service.service.TaxReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Tax analysis (design §18). from/to default to the last 12 months; groupBy = month | taxCode | component | category. */
@RestController
@RequiredArgsConstructor
public class TaxReportController {

    private final TaxReportService taxReportService;

    @GetMapping("/xms/reports/tax")
    @PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE','ADMIN','SUPER_ADMIN')")
    public ApiResponse<TaxReportResponse> taxReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "month") String groupBy,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID taxCodeId) {
        return ApiResponse.success(taxReportService.report(from, to, groupBy, categoryId, taxCodeId));
    }
}
