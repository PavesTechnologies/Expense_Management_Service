-- Production-readiness audit (Part 12): explicit composite indexes for the query patterns Phases
-- 3-8 actually introduced. Every FK column here (report_id, line_item_id, split_id, assignment_id,
-- budget_id) is already indexed as a side effect of its own FK constraint (InnoDB auto-indexes the
-- referencing column) - these three indexes are for the COMPOSITE lookups the repository layer
-- actually issues, which a single-column FK index cannot serve efficiently on its own:
--   - BudgetEncumbranceRepository.findByReport_ReportIdAndSubmissionCycleAndStatus - the hot path
--     for release/consume/reconcile, called on nearly every submit/recall/cancel/reject/resume.
--   - ExpenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc - the
--     "current split state" read, now filtering on removed_at on every call since the production-
--     readiness audit's soft-delete reconciliation.
--   - ApprovalAssignmentRepository.findDistinctReportIdsByStatusAndApproverIdIn - "My Queue"'s own
--     query, filtering by (status, approver_id) across the whole table.
--
-- PRECONDITION: expense_split, budget_encumbrance, and approval_assignment (with its status/
-- approver_id columns) are additive tables/columns left to hibernate.ddl-auto=update (see V15's own
-- precedent) - this migration must only run after an app deploy has already created them. Verify via
-- information_schema.STATISTICS / information_schema.COLUMNS against the target database before
-- running this in any environment where that prior deploy hasn't happened yet.
--
-- MySQL 8 does not support "CREATE INDEX IF NOT EXISTS" - normally that's fine, since Flyway's own
-- versioned-migration guarantee runs this file at most once per database. This one is the exception:
-- during the production-readiness audit's real-DB concurrency testing, these 3 exact indexes were
-- applied directly (via raw SQL, with sign-off) to the shared dev database to avoid running the
-- untested V1-V16 Flyway history for the first time against it - which means Flyway's own
-- flyway_schema_history was never told V16 is done, and its first real run fails with "Duplicate key
-- name" the moment it tries to (re)create an index that's already there. Each statement below is
-- therefore guarded with an information_schema.STATISTICS check (MySQL's standard idempotent-DDL
-- workaround) so this migration succeeds - as a no-op for an index that already exists, or as a real
-- CREATE INDEX on any database (e.g. a fresh environment) where it doesn't - either way letting
-- Flyway record V16 as successfully applied exactly once, with no manual schema_history edits needed.

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'budget_encumbrance'
      AND INDEX_NAME = 'idx_budget_encumbrance_report_cycle_status');
SET @ddl = IF(@idx_exists = 0,
    'CREATE INDEX idx_budget_encumbrance_report_cycle_status ON budget_encumbrance (report_id, submission_cycle, status)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'expense_split'
      AND INDEX_NAME = 'idx_expense_split_line_item_removed_at');
SET @ddl = IF(@idx_exists = 0,
    'CREATE INDEX idx_expense_split_line_item_removed_at ON expense_split (line_item_id, removed_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'approval_assignment'
      AND INDEX_NAME = 'idx_approval_assignment_status_approver');
SET @ddl = IF(@idx_exists = 0,
    'CREATE INDEX idx_approval_assignment_status_approver ON approval_assignment (status, approver_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
