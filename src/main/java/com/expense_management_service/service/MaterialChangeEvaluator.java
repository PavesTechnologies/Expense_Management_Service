package com.expense_management_service.service;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseReport;

/**
 * Decides, for a Finance-originated correction, whether the employee's fix changed the report
 * enough to require full re-approval starting at Manager - a different question from "does a
 * different {@code ApprovalFlow} now match" (some material dimensions here, e.g. client-billable,
 * are never flow-matching criteria at all). Never consulted for a Manager-originated correction -
 * that path keeps using only "does the same flow still match", unchanged.
 */
public interface MaterialChangeEvaluator {

    /**
     * @param materializedSnapshot any {@code ApprovalLevelInstance} of the current cycle - all of
     *                             them carry an identical materialization-time snapshot.
     */
    boolean hasMaterialChange(ExpenseReport report, ApprovalLevelInstance materializedSnapshot);

    /**
     * The exact fingerprint format snapshotted onto {@code ApprovalLevelInstance.materializedGlAccountFingerprint}
     * at materialization time - single-sourced here so the snapshot and the later comparison can
     * never drift out of format sync.
     */
    String computeGlAccountFingerprint(ExpenseReport report);
}
