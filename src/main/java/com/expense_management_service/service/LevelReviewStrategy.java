package com.expense_management_service.service;

import com.expense_management_service.entity.ApprovalLevelInstance;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.LevelType;

import java.util.List;

/**
 * The level-type-specific half of level review: which review table a level's line items are
 * tracked in, and what "every line item has reached a positive outcome at this level" means for
 * that table. {@code ApprovalWorkflowServiceImpl} owns everything else (quorum handling,
 * SEQUENTIAL advancement, level/report completion, events) - that part never varies by level type.
 * <p>
 * Deliberately does NOT include an approver-resolution hook: {@code
 * ApproverSourceResolver}/{@code ApprovalLevelApprover} already resolve approvers uniformly
 * regardless of {@code levelType} (a FINANCE_VERIFICATION level is just a level whose entries
 * happen to use {@code ApproverSourceType.FINANCE_OWNER}), so a delegating no-op method here would
 * be dead code.
 */
public interface LevelReviewStrategy {

    LevelType levelType();

    /** Creates one fresh, pending review row per line item for a just-activated (or re-activated, SEQUENTIAL) instance. */
    void createPendingReviews(ApprovalLevelInstance instance, List<ExpenseLineItem> lineItems);

    /** True once every line item's review at this instance has reached the level's terminal positive outcome. */
    boolean isLevelComplete(ApprovalLevelInstance instance);

    /** Resets every review at this instance back to its initial pending state - used only for a SEQUENTIAL entry's fresh pass. */
    void resetPendingReviews(ApprovalLevelInstance instance);

    /**
     * Resumes correction in place after {@code AWAITING_CORRECTION}: resets only the reviews the
     * correction actually flagged (NEEDS_CORRECTION for Approval / QUERIED for Finance) back to
     * pending, leaving every already-positive review at this instance untouched. Distinct from
     * {@link #resetPendingReviews}, which unconditionally resets every review at the instance for a
     * fresh SEQUENTIAL pass.
     */
    void resumeCorrectedReviews(ApprovalLevelInstance instance);
}
