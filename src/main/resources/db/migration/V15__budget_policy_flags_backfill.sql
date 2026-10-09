-- Expense Split / Budget Encumbrance (Phase 1): cost_center_budget.allow_rollover and
-- cost_center.allow_unbudgeted are the only two new budget-policy fields that need a migration at
-- all - rollover_from_previous, rollover_cap, warning_threshold, and the three new tables
-- (expense_split, approval_split_review, budget_encumbrance) are purely additive/nullable and are
-- left entirely to hibernate.ddl-auto=update, consistent with this project's established convention
-- (see V1, V5, V9, V10, V13, V14). These two are different: they are meant to be strict, non-null
-- governance flags (a NULL value would be ambiguous - "not yet decided" vs "false" - for a flag
-- whose whole purpose is a clear yes/no), so any existing row must be backfilled before the NOT NULL
-- constraint can be locked in, same as V9 (policy_bundle_id) / V10 (enforcement_type) /
-- V13 (level_type) each needed to backfill a similarly newly-non-null column.
--
-- PRECONDITION: cost_center_budget.allow_rollover and cost_center.allow_unbudgeted are NOT created
-- by this script - they are additive columns left to hibernate.ddl-auto=update, picking up the new
-- CostCenterBudget.allowRollover / CostCenter.allowUnbudgeted fields. This migration must only run
-- after an app deploy has already created them (nullable at that point). Verify via
-- information_schema.COLUMNS against the target database before running this in any environment
-- where that prior deploy hasn't happened yet.
--
-- Backfilling to FALSE preserves today's exact behavior for every already-configured cost center -
-- no rollover, no unbudgeted allowance - until an admin explicitly opts a cost center in.

UPDATE cost_center_budget
SET allow_rollover = FALSE
WHERE allow_rollover IS NULL;

ALTER TABLE cost_center_budget
    MODIFY COLUMN allow_rollover BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE cost_center
SET allow_unbudgeted = FALSE
WHERE allow_unbudgeted IS NULL;

ALTER TABLE cost_center
    MODIFY COLUMN allow_unbudgeted BOOLEAN NOT NULL DEFAULT FALSE;
