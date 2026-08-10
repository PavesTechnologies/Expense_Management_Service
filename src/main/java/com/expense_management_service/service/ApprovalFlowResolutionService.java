package com.expense_management_service.service;

import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ExpenseReport;

/**
 * Evaluates configured {@link ApprovalFlow}s against a specific report and returns the winning one.
 * Flows are evaluated in ascending priority order; the first whose criteria match wins. Falls back
 * to the mandatory catch-all flow if none match - routing must never fail to resolve (§5.6).
 */
public interface ApprovalFlowResolutionService {

    ApprovalFlow resolveMatchingFlow(ExpenseReport report);
}
