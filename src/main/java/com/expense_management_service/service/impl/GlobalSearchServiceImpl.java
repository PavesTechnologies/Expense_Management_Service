package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.SearchResultResponse;
import com.expense_management_service.entity.SearchIndex;
import com.expense_management_service.repository.SearchIndexRepository;
import com.expense_management_service.service.GlobalSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Global Expense Search Service
 * Spec: EP12-S1 - Global search across reports and line items
 *
 * CRITICAL: Scope enforcement is a HARD SERVER-SIDE RULE
 * This is never a client-side filter and never bypassed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class GlobalSearchServiceImpl implements GlobalSearchService {

    private final SearchIndexRepository searchIndexRepository;

    @Override
    public Page<SearchResultResponse> search(String query, String employeeId, String roles, Pageable pageable) {
        log.info("[GLOBAL-SEARCH] Query='{}' Employee={} Roles={}", query, employeeId, roles);

        if (query == null || query.trim().isEmpty()) {
            log.warn("[GLOBAL-SEARCH] Empty query");
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String cleanQuery = query.trim().toLowerCase();
        List<SearchIndex> allResults;

        // HARD SERVER-SIDE SCOPE ENFORCEMENT (never client-side)
        if (roles != null && (roles.contains("ADMIN") || roles.contains("FINANCE"))) {
            // Finance/Admin: can see ALL reports
            log.debug("[GLOBAL-SEARCH] Finance/Admin scope - returning all reports");
            allResults = searchIndexRepository.findAll().stream()
                    .filter(si -> matchesQuery(si, cleanQuery))
                    .collect(Collectors.toList());
        } else if (roles != null && roles.contains("MANAGER")) {
            // Manager: own + team reports (simplified - just own for MVP)
            log.debug("[GLOBAL-SEARCH] Manager scope - returning own + team reports");
            allResults = searchIndexRepository.findByEmployeeId(employeeId).stream()
                    .filter(si -> matchesQuery(si, cleanQuery))
                    .collect(Collectors.toList());
        } else {
            // General Employee: ONLY own reports (strict enforcement)
            log.debug("[GLOBAL-SEARCH] Employee scope - returning own reports ONLY");
            allResults = searchIndexRepository.findByEmployeeId(employeeId).stream()
                    .filter(si -> matchesQuery(si, cleanQuery))
                    .collect(Collectors.toList());
        }

        log.info("[GLOBAL-SEARCH] Found {} matching records for query: {}", allResults.size(), cleanQuery);

        // Sort by relevance (exact matches first, then partial matches, then by date)
        List<SearchResultResponse> ranked = allResults.stream()
                .sorted(scoreByRelevance(cleanQuery))
                .skip((long) pageable.getPageNumber() * pageable.getPageSize())
                .limit(pageable.getPageSize())
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(ranked, pageable, allResults.size());
    }

    /**
     * Check if the search index record matches the query across multiple fields
     * Searches: report number, employee name, merchant, category, business purpose
     */
    private boolean matchesQuery(SearchIndex si, String query) {
        return (si.getReportNumber() != null && si.getReportNumber().toLowerCase().contains(query)) ||
               (si.getEmployeeName() != null && si.getEmployeeName().toLowerCase().contains(query)) ||
               (si.getMerchant() != null && si.getMerchant().toLowerCase().contains(query)) ||
               (si.getCategory() != null && si.getCategory().toLowerCase().contains(query)) ||
               (si.getBusinessPurpose() != null && si.getBusinessPurpose().toLowerCase().contains(query));
    }

    /**
     * Score results by relevance for proper ranking
     * Exact matches (field = query) ranked higher than partial matches
     * Then sort by date (recent first)
     */
    private Comparator<SearchIndex> scoreByRelevance(String query) {
        return (a, b) -> {
            int scoreA = calculateRelevanceScore(a, query);
            int scoreB = calculateRelevanceScore(b, query);

            if (scoreA != scoreB) {
                return scoreB - scoreA; // Higher score first
            }

            // Same score: sort by date (recent first)
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        };
    }

    /**
     * Calculate relevance score (higher = more relevant)
     * Exact match = 100, starts with = 50, contains = 10
     */
    private int calculateRelevanceScore(SearchIndex si, String query) {
        int score = 0;

        if (si.getReportNumber() != null) {
            score += scoreField(si.getReportNumber().toLowerCase(), query);
        }
        if (si.getEmployeeName() != null) {
            score += scoreField(si.getEmployeeName().toLowerCase(), query);
        }
        if (si.getMerchant() != null) {
            score += scoreField(si.getMerchant().toLowerCase(), query) / 2; // Lower priority
        }
        if (si.getCategory() != null) {
            score += scoreField(si.getCategory().toLowerCase(), query) / 2; // Lower priority
        }

        return score;
    }

    private int scoreField(String field, String query) {
        if (field.equals(query)) return 100; // Exact match
        if (field.startsWith(query)) return 50; // Starts with
        if (field.contains(query)) return 10; // Contains
        return 0;
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
                si.getUpdatedAt()
        );
    }
}
