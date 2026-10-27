-- ============================================================================
-- V39: deadline override fields on expense_report, and idempotency index on cash_advance_adjustment
--
-- MANUAL RUN ONLY. Do NOT run until: (1) the V30/V31 history is reconciled, (2) V38 is reviewed, (3) a backup exists,
-- and (4) the owner gives explicit confirmation for the named dev database. See docs/design/migration-plan-v38-v39.md.
--
-- Safe to re-run: every change is guarded by information_schema.
-- Requires expense_report and cash_advance_adjustment to exist.
--
-- Pre-check (read-only, run first). Must return 0 rows, or the unique index below will fail:
--   SELECT idempotency_key, COUNT(*) FROM cash_advance_adjustment
--    WHERE idempotency_key IS NOT NULL GROUP BY idempotency_key HAVING COUNT(*) > 1;
-- ============================================================================

-- expense_deadline_extended_until: Finance extension of this report's submission deadline.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'expense_report' AND column_name = 'expense_deadline_extended_until') = 0,
  'ALTER TABLE expense_report ADD COLUMN expense_deadline_extended_until DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- expense_deadline_reopened: Finance reopen; lifts the submission check for this report.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'expense_report' AND column_name = 'expense_deadline_reopened') = 0,
  'ALTER TABLE expense_report ADD COLUMN expense_deadline_reopened BIT(1) NOT NULL DEFAULT b''0''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Idempotency: a retried offset with the same key must not be applied twice.
-- The column idempotency_key already exists on dev (created by ddl-auto=update). Add it only if it is missing.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_adjustment' AND column_name = 'idempotency_key') = 0,
  'ALTER TABLE cash_advance_adjustment ADD COLUMN idempotency_key VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The repo's V30 already declares idempotency_key UNIQUE, and dev has that unique key (UK6hfwdko3q5nclp6ec2dffrtm5,
-- found by the read-only audit). Create the index only if no unique index on this column exists at all.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_adjustment' AND column_name = 'idempotency_key'
      AND non_unique = 0) = 0,
  'CREATE UNIQUE INDEX uk_cash_adjustment_idempotency_key ON cash_advance_adjustment (idempotency_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Verification (read-only)
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'expense_report' AND column_name IN ('expense_deadline_extended_until', 'expense_deadline_reopened'))
    OR (table_name = 'cash_advance_adjustment' AND column_name = 'idempotency_key'))
ORDER BY table_name, column_name;
