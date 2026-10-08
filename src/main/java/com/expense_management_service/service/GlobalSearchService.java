package com.expense_management_service.service;

import com.expense_management_service.dto.response.SearchResultResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface GlobalSearchService {

    /**
     * Search expense reports with role-based scope.
     * - Employee: only their own reports
     * - Manager: own reports + team reports
     * - Finance/Admin: all reports
     * Frontend applies additional filters (date, amount, status, etc.)
     */
    Page<SearchResultResponse> search(String employeeId, String role, Pageable pageable);
}
