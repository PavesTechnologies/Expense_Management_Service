-- ============================================================================
-- V36: trip dates on expense reports (submission deadline = trip_end_date + expense.submission.deadline-days)
--
-- MANUAL RUN ONLY. Flyway is disabled in this project (spring.flyway.enabled=false) and ddl-auto=update
-- is on. Run on DEV first. Do not run on the shared database without approval.
--
-- Safe to re-run. Existing reports are NOT backfilled: their trip dates stay NULL, so they must be given a
-- trip end date before they can be submitted.
-- ============================================================================

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'expense_report' AND column_name = 'trip_start_date') = 0,
  'ALTER TABLE expense_report ADD COLUMN trip_start_date DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'expense_report' AND column_name = 'trip_end_date') = 0,
  'ALTER TABLE expense_report ADD COLUMN trip_end_date DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Verification (read-only)
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'expense_report'
  AND column_name IN ('trip_start_date', 'trip_end_date')
ORDER BY column_name;
