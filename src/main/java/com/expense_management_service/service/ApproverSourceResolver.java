package com.expense_management_service.service;

import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.ExpenseReport;

import java.util.Optional;

/**
 * Resolves one configured {@link ApprovalLevelApprover} entry to an actual EOS {@code employeeId}
 * for a specific report. Replaces EP06's {@code ApproverResolver} - same 4 underlying concepts
 * (named user, manager, cost-center owner, +new department owner), resolved against
 * {@code ApprovalLevelApprover} instead of {@code ApprovalMatrix}.
 */
public interface ApproverSourceResolver {

    Optional<String> resolve(ApprovalLevelApprover entry, ExpenseReport report);
}
