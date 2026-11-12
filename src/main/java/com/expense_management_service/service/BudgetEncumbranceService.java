package com.expense_management_service.service;

import com.expense_management_service.dto.response.BudgetEncumbranceOutcome;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseReport;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Owns the entire {@code BudgetEncumbrance} lifecycle (Decisions 5/6/7 - Effective Available Budget,
 * ACTIVE/RELEASED/CONSUMED, pessimistic-locked validation). Deliberately NOT yet called from
 * {@code ApprovalWorkflowServiceImpl} or {@code ApPaymentServiceImpl} - this phase builds and fully
 * tests the capability standalone; wiring it into submission (Phase 4/5) and AP completion (Phase 6)
 * is later, separately-scoped work, per the agreed phase boundary.
 */
public interface BudgetEncumbranceService {

    /**
     * Validates and atomically creates ACTIVE encumbrances for a report at the given submission
     * cycle - one row per {@code ExpenseSplit} across every line item that has any, plus, if any
     * line items have none, ONE additional row for their combined "unsplit remainder" against
     * {@code report.getCostCenter()} (mirrors exactly how {@code consumeBudget} already treats a
     * fully-normal report's whole total today). Splits/remainders sharing the same Cost Center are
     * validated as one combined need, never checked twice against the same shrinking figure.
     * <p>
     * All-or-nothing: if ANY target's Cost Center has insufficient Effective Available Budget (and
     * is not covered by {@code CostCenter.allowUnbudgeted}), the whole call throws and creates
     * nothing. A missing budget row is only tolerated when {@code allowUnbudgeted == true}, in which
     * case that specific row is created with {@code unbudgeted = true} and no validation is possible
     * or attempted against it.
     *
     * @return the created encumbrances plus any non-blocking Cost-Center-Owner-facing warnings
     */
    BudgetEncumbranceOutcome validateAndEncumber(ExpenseReport report, int submissionCycle);

    /** Releases every currently-ACTIVE encumbrance for one report/cycle - for reject, recall, cancel, and the start of a material-change restart. A no-op if none are ACTIVE. */
    void releaseActiveForCycle(UUID reportId, int submissionCycle);

    /**
     * Consumes every currently-ACTIVE encumbrance for one report/cycle via the existing, unmodified
     * {@code CostCenterBudgetService.consumeBudget} - called once, at AP payment completion (Phase 6).
     * An unbudgeted encumbrance is marked CONSUMED with no budget decrement, since there is nothing
     * configured to decrement.
     *
     * @return true if at least one ACTIVE encumbrance was found and consumed; false if none existed
     * for this report/cycle (e.g. a report that reached APPROVED before this encumbrance wiring
     * existed) - callers should fall back to their own pre-existing consumption path in that case,
     * never call this a second time for the same report/cycle regardless of the result.
     */
    boolean consumeActiveForReport(UUID reportId, int submissionCycle);

    /** {@code availableBudget - SUM(ACTIVE encumbrances against this specific budget row)} - the read-time formula from Decision 6, exposed for reuse (also used by rollover validation's "true unencumbered remainder" check). */
    BigDecimal effectiveAvailable(CostCenterBudget budget);

    /**
     * Production-readiness audit (Part 1): reconciles a cycle's ACTIVE encumbrances against the
     * report's CURRENT split/line-item state - called from {@code ApprovalWorkflowServiceImpl}'s
     * {@code resumeInPlace}, the one correction path that can change budget-impacting data (amounts,
     * Cost Centers, added/removed splits) without going through {@code fullRestart}'s materialize-a-
     * new-cycle path. A no-op if the current targets already match the ACTIVE set exactly (same
     * split identities, same Cost Centers, same amounts) - never releases and recreates encumbrances
     * that didn't actually change. Otherwise releases the stale set and re-validates+re-encumbers
     * atomically for the SAME cycle; if the corrected state now needs more budget than is available,
     * this throws and the whole call (including the release) rolls back with it - resumeInPlace must
     * never be left with an old encumbrance partially released and no valid new one in its place.
     */
    void reconcileForCycle(ExpenseReport report, int submissionCycle);
}
