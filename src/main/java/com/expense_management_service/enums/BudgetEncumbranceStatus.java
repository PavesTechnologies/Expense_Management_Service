package com.expense_management_service.enums;

/**
 * Lifecycle of one {@code BudgetEncumbrance} - a reservation against a {@code CostCenterBudget},
 * deliberately distinct from actual consumption (see {@code CostCenterBudgetService.consumeBudget}).
 * An encumbrance never mutates {@code CostCenterBudget.availableBudget} itself; Effective Available
 * Budget is computed at read time as {@code availableBudget - SUM(ACTIVE encumbrances)}.
 */
public enum BudgetEncumbranceStatus {
    /** Reserved - counted against Effective Available Budget. Not yet paid or withdrawn. */
    ACTIVE,
    /** Withdrawn without payment (correction, restart, recall, cancellation, or rejection). Terminal - never resurrected; a later re-encumbrance always creates a new row. */
    RELEASED,
    /** Paid - the reservation became an actual ledger deduction via consumeBudget(). Terminal. */
    CONSUMED
}
