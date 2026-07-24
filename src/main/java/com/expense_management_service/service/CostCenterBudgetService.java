package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import java.util.List;
import java.util.UUID;

public interface CostCenterBudgetService {

    CostCenterBudgetResponse create(CostCenterBudgetRequest request);

    CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request);

    CostCenterBudgetResponse getById(UUID budgetId);

    List<CostCenterBudgetResponse> getAll();

    void delete(UUID budgetId);
}
