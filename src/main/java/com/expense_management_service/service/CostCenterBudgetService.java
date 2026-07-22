package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CostCenterBudgetService {

    CostCenterBudgetResponse create(CostCenterBudgetRequest request);

    CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request);

    CostCenterBudgetResponse getById(UUID budgetId);

    Page<CostCenterBudgetResponse> getAll(Pageable pageable);

    void delete(UUID budgetId);
}
