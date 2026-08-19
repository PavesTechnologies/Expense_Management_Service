package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CostCenterBudgetService {

    CostCenterBudgetResponse create(CostCenterBudgetRequest request);

    CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request);

    CostCenterBudgetResponse getById(UUID budgetId);

    List<CostCenterBudgetResponse> getAll();

    void delete(UUID budgetId);

    /**
     * Decrements {@code availableBudget} for the cost center's budget in the given fiscal year by
     * {@code amount} - called exactly once per report, at final Finance approval (see {@code
     * ApprovalWorkflowServiceImpl.applyPaymentRouting}), which owns the idempotency guard; this
     * method itself always consumes when called. A no-op (logged) if no budget row is configured
     * for that cost center/fiscal year - an unbudgeted cost center is not an error condition today.
     * Never throws for an over-budget result - {@code availableBudget} is allowed to go negative;
     * no blocking rule exists in the current system (see the implementation report).
     */
    void consumeBudget(CostCenter costCenter, String fiscalYear, BigDecimal amount);
}
