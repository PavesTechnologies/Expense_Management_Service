package com.expense_management_service.service;

import com.expense_management_service.entity.Policy;

/**
 * The single source of truth for which {@link Policy} governs a given employee. Every caller that
 * needs an employee's effective policy — rule evaluation, admin lookups, any future
 * submission-time check — must go through this resolver rather than re-implementing the
 * Individual &gt; Group &gt; Default precedence itself.
 */
public interface PolicyAssignmentResolver {

    /**
     * Resolves the single active policy governing {@code employeeId}, using Individual &gt; Group
     * &gt; Default precedence. {@code employeeId} may be {@code null} (e.g. a line item whose
     * report has none set) — resolution simply skips straight to Default in that case. Never
     * returns {@code null}: exactly one active {@code DEFAULT} assignment is guaranteed to exist
     * by the Phase 1 seed migration.
     */
    Policy resolve(String employeeId);
}
