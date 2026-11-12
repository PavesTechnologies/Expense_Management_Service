package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.SearchResultResponse;
import com.expense_management_service.service.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * Global Expense Search API
 * Spec: EP12-S1 - Global search across reports and line items
 *
 * Scope enforcement: HARD SERVER-SIDE RULE (never client-side)
 * - Employee: own reports only
 * - Manager: own + team reports
 * - Finance/Auditor/Admin: all reports
 *
 * Endpoint: GET /xms/api/v1/search?q=<query>&scope=<scope>&page=0&size=20
 */
@RestController
@RequestMapping("/xms/api/v1/search")
@RequiredArgsConstructor
@Slf4j
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    /**
     * Free-text global expense search with role-based scope enforcement.
     *
     * Search searches: employee name, merchant, category, business purpose, report number
     * Scope enforcement: Server-side, based on user's role and identity
     *
     * @param q search query (free-text across multiple fields)
     * @param page page number (0-indexed, default 0)
     * @param size page size (default 20, max 100)
     * @param authentication Spring Security authentication object (has employee ID & roles)
     * @return paginated search results ranked by relevance, or suggestions if zero results
     */
    @GetMapping
    public ApiResponse<Page<SearchResultResponse>> search(
            @RequestParam(required = true) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {

        if (q == null || q.trim().isEmpty()) {
            return ApiResponse.error("Search query cannot be empty");
        }

        if (size > 100) size = 100;

        String employeeId = authentication.getName();
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        log.info("Global search: query='{}', user={}, roles={}, page={}, size={}",
                 q, employeeId, roles, page, size);

        Pageable pageable = PageRequest.of(page, size);
        Page<SearchResultResponse> results = globalSearchService.search(q, employeeId, roles, pageable);

        if (results.isEmpty()) {
            log.info("Search returned 0 results for query: {}", q);
            return ApiResponse.success(
                    "No results found. Try different keywords or check if you have access to those reports.",
                    results);
        }

        log.info("Search returned {} results for query: {}", results.getTotalElements(), q);
        return ApiResponse.success("Search results", results);
    }
}
