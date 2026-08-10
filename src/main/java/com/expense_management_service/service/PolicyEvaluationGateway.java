package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseReport;

/**
 * The sole seam between the Approval Flow Engine and whatever the (separately-built) Policy Engine
 * turns out to be (§10). The Approval Engine calls this at submission and reacts only to
 * {@link PolicyDecision#allowed()} - never to specific rule types/severities - so a future Policy
 * Engine rebuild (e.g. adding a genuine BLOCK tier) plugs in here without any Approval Engine
 * rework. Today's interim adapter wraps the existing advisory-only {@code PolicyEvaluator}
 * (WARN/INFO only) and always returns {@code allowed = true}.
 */
public interface PolicyEvaluationGateway {

    PolicyDecision evaluate(ExpenseReport report);
}
