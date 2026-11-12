package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseReport;

/**
 * Runs the two automatic correctness passes over a freshly-materialised (but not yet activated)
 * resolved chain: self-approval strip-and-replace (§5.1), then duplicate-approver de-dup (§2.6).
 * Self-approval runs first so de-dup operates on each assignment's FINAL resolved approver, not a
 * pre-substitution one.
 */
public interface ChainCorrectnessService {

    void applyCorrectnessPasses(ExpenseReport report, int submissionCycle);
}
