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
     * {@code amount} - called exactly once per report, at AP payment completion (see {@code
     * ApPaymentServiceImpl.markPaymentCompleted}), which owns the idempotency guard (its own
     * "already APPROVED_FOR_PAYMENT?" check makes a duplicate completion attempt fail before ever
     * reaching this call); this method itself always consumes when called. Deliberately NOT called
     * at submission, Manager approval, Finance verification, or the APPROVED_FOR_PAYMENT routing
     * decision (see {@code ApprovalWorkflowServiceImpl.applyPaymentRouting}) - none of those are the
     * point at which the organization actually pays out, so none of them should move real budget. A
     * no-op (logged) if no budget row is configured for that cost center/fiscal year - an unbudgeted
     * cost center is not an error condition today. Never throws for an over-budget result -
     * {@code availableBudget} is allowed to go negative; no blocking rule exists in the current system.
     */
    void consumeBudget(CostCenter costCenter, String fiscalYear, BigDecimal amount);
}
