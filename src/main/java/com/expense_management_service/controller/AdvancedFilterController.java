package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.ExpenseFilterRequest;
import com.expense_management_service.dto.response.SearchResultResponse;
import com.expense_management_service.service.AdvancedFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Advanced Multi-Criteria Filter API
 * Spec: EP12-S2 - Advanced filters for precise expense data narrowing
 *
 * Endpoint: GET /api/v1/expense-reports?status=&dateFrom=&dateTo=&category=&costCenter=&amountMin=&amountMax=&billable=
 *
 * Features:
 * - Multi-criteria filtering (status, date range, amount range, category, cost center, employee)
 * - Amount range validation (min <= max)
 * - Role-based scope enforcement (server-side)
 * - Conflicting filters return empty set (no error)
 * - URL-encoded filters for shareable/bookmarkable views
 * - Reactive results on client
 */
@RestController
@RequestMapping("/api/v1/expense-reports")
@RequiredArgsConstructor
@Slf4j
public class AdvancedFilterController {

    private final AdvancedFilterService advancedFilterService;

    /**
     * Apply advanced multi-criteria filters to expense reports
     *
     * Query Parameters (all optional):
     * - status: Report status (DRAFT, SUBMITTED, APPROVED, REJECTED, REIMBURSED)
     * - dateFrom: Filter reports created from (YYYY-MM-DD)
     * - dateTo: Filter reports created until (YYYY-MM-DD)
     * - category: Expense category name
     * - costCenter: Cost center UUID or name
     * - amountMin: Minimum amount (validates min <= max)
     * - amountMax: Maximum amount (validates min <= max)
     * - employeeId: Filter by employee ID
     * - billable: Filter billable/non-billable (true/false)
     * - page: Page number (0-indexed, default 0)
     * - size: Page size (default 20, max 100)
     *
     * @return paginated filtered results
     */
    @GetMapping
    public ApiResponse<Page<SearchResultResponse>> filterExpenses(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String costCenter,
            @RequestParam(required = false) Double amountMin,
            @RequestParam(required = false) Double amountMax,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) Boolean billable,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        // Validate amount range
        if (amountMin != null && amountMax != null && amountMin > amountMax) {
            return ApiResponse.error("Invalid amount range: amountMin must be <= amountMax");
        }

        if (size > 100) size = 100;

        String userEmployeeId = authentication.getName();
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        log.info("[ADVANCED-FILTER] Request: status={}, dateFrom={}, dateTo={}, amountMin={}, amountMax={}, page={}, size={}",
                 status, dateFrom, dateTo, amountMin, amountMax, page, size);

        // Build filter request
        ExpenseFilterRequest filter = new ExpenseFilterRequest(
                status,
                dateFrom,
                dateTo,
                category,
                costCenter,
                amountMin,
                amountMax,
                employeeId,
                billable
        );

        Pageable pageable = PageRequest.of(page, size);
        Page<SearchResultResponse> results = advancedFilterService.filter(filter, userEmployeeId, roles, pageable);

        log.info("[ADVANCED-FILTER] Returned {} of {} total results",
                 results.getNumberOfElements(), results.getTotalElements());

        return ApiResponse.success("Filtered results", results);
    }
}
