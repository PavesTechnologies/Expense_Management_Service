package com.expense_management_service.service;

import com.expense_management_service.dto.request.ExpenseFilterRequest;
import com.expense_management_service.dto.response.SearchResultResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * EP12-S2: Advanced Multi-Criteria Filter Service
 * Supports precise, structured narrowing of expense data by combining multiple criteria.
 *
 * Business Rules:
 * - Amount range filter validates min ≤ max before applying
 * - Conflicting filter combinations show empty result set (no error)
 * - Filter combinations encoded in URL for shareable/bookmarkable views
 * - Results update reactively on client
 */
public interface AdvancedFilterService {

    /**
     * Apply advanced multi-criteria filters to expense reports
     *
     * @param filter filter criteria (status, date range, amount range, category, etc.)
     * @param employeeId current user's employee ID (for scope enforcement)
     * @param roles current user's roles (for scope enforcement)
     * @param pageable pagination info
     * @return filtered and paginated results
     */
    Page<SearchResultResponse> filter(
            ExpenseFilterRequest filter,
            String employeeId,
            String roles,
            Pageable pageable);
}
