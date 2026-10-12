package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.ExpenseFilterRequest;
import com.expense_management_service.dto.response.SearchResultResponse;
import com.expense_management_service.entity.SearchIndex;
import com.expense_management_service.repository.SearchIndexRepository;
import com.expense_management_service.service.AdvancedFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * EP12-S2: Advanced Multi-Criteria Filter Service Implementation
 *
 * Features:
 * - Amount range validation (min <= max)
 * - Date range filtering
 * - Multi-field filtering (status, category, cost center, etc.)
 * - Empty result sets for conflicting filters (no error)
 * - Role-based scope enforcement
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdvancedFilterServiceImpl implements AdvancedFilterService {

    private final SearchIndexRepository searchIndexRepository;

    @Override
    public Page<SearchResultResponse> filter(
            ExpenseFilterRequest filter,
            String employeeId,
            String roles,
            Pageable pageable) {

        log.info("[ADVANCED-FILTER] Applying filters for user={}, roles={}", employeeId, roles);

        // Validate amount range
        if (filter.amountMin() != null && filter.amountMax() != null) {
            if (filter.amountMin() > filter.amountMax()) {
                log.warn("[ADVANCED-FILTER] Invalid amount range: min={} > max={}",
                         filter.amountMin(), filter.amountMax());
                return new PageImpl<>(List.of(), pageable, 0);
            }
        }

        // Fetch all reports based on role-based scope
        List<SearchIndex> allReports = fetchReportsByScope(employeeId, roles);

        // Apply filters
        List<SearchIndex> filtered = allReports.stream()
                .filter(r -> matchesAllFilters(r, filter))
                .collect(Collectors.toList());

        log.info("[ADVANCED-FILTER] Filtered {} reports -> {} results", allReports.size(), filtered.size());

        // Paginate results
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filtered.size());
        List<SearchResultResponse> pageContent = filtered.subList(start, end).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(pageContent, pageable, filtered.size());
    }

    /**
     * Fetch reports based on role-based scope (same as GlobalSearch)
     */
    private List<SearchIndex> fetchReportsByScope(String employeeId, String roles) {
        if (roles.contains("ADMIN") || roles.contains("FINANCE")) {
            return searchIndexRepository.findAll();
        } else {
            return searchIndexRepository.findByEmployeeId(employeeId);
        }
    }

    /**
     * Check if a report matches all active filters
     */
    private boolean matchesAllFilters(SearchIndex report, ExpenseFilterRequest filter) {
        // Status filter
        if (filter.status() != null && !filter.status().isBlank()) {
            if (!report.getStatus().equalsIgnoreCase(filter.status())) {
                return false;
            }
        }

        // Date range filter
        if (filter.dateFrom() != null) {
            if (report.getCreatedAt() == null ||
                report.getCreatedAt().toLocalDate().isBefore(filter.dateFrom())) {
                return false;
            }
        }
        if (filter.dateTo() != null) {
            if (report.getCreatedAt() == null ||
                report.getCreatedAt().toLocalDate().isAfter(filter.dateTo())) {
                return false;
            }
        }

        // Amount range filter
        if (filter.amountMin() != null) {
            if (report.getAmount() == null || report.getAmount() < filter.amountMin()) {
                return false;
            }
        }
        if (filter.amountMax() != null) {
            if (report.getAmount() == null || report.getAmount() > filter.amountMax()) {
                return false;
            }
        }

        // Category filter
        if (filter.category() != null && !filter.category().isBlank()) {
            if (report.getCategory() == null ||
                !report.getCategory().equalsIgnoreCase(filter.category())) {
                return false;
            }
        }

        // Cost center filter
        if (filter.costCenter() != null && !filter.costCenter().isBlank()) {
            if (report.getCostCenter() == null ||
                !report.getCostCenter().equalsIgnoreCase(filter.costCenter())) {
                return false;
            }
        }

        // Employee filter
        if (filter.employeeId() != null && !filter.employeeId().isBlank()) {
            if (!report.getEmployeeId().equalsIgnoreCase(filter.employeeId())) {
                return false;
            }
        }

        return true;
    }

    private SearchResultResponse toResponse(SearchIndex si) {
        return new SearchResultResponse(
                si.getReportId(),
                si.getReportNumber(),
                si.getEmployeeId(),
                si.getEmployeeName(),
                si.getMerchant(),
                si.getCategory(),
                si.getCostCenter(),
                si.getBusinessPurpose(),
                si.getStatus(),
                si.getAmount(),
                si.getCreatedAt(),
                si.getCreatedAt()
        );
    }
}
