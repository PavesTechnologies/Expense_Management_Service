-- V39: Reversible cash advance adjustments (Milestone 1).
--
-- Financial history is never deleted. An offset is reversed by setting reversed = 1 and recording who, when and why.
-- A reversed offset can be reapplied to the same report as a NEW row. The database keeps at most one ACTIVE offset per
-- (advance, report), using the generated column active_offset_key, which is NULL for reversed rows. MySQL allows many
-- NULLs in a unique index, so reversed rows never conflict.
--
-- PRE-FLIGHT (run manually before deploying; this migration does not stop itself):
--   SELECT advance_id, report_id, COUNT(*) FROM cash_advance_adjustment
--    WHERE reversed = 0 GROUP BY advance_id, report_id HAVING COUNT(*) > 1;
--   -- must return zero rows, otherwise the unique index below cannot be created.
--
-- Every step is guarded, so the script is safe on a database where ddl-auto already created some of these objects.

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD COLUMN reversed BIT(1) NOT NULL DEFAULT b''0''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND COLUMN_NAME = 'reversed');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD COLUMN reversed_at DATETIME(6) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND COLUMN_NAME = 'reversed_at');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD COLUMN reversed_by VARCHAR(255) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND COLUMN_NAME = 'reversed_by');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD COLUMN reversal_reason VARCHAR(1000) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND COLUMN_NAME = 'reversal_reason');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD COLUMN active_offset_key VARCHAR(80) GENERATED ALWAYS AS (IF(reversed = b''0'', CONCAT(HEX(advance_id), '':'', HEX(report_id)), NULL)) STORED', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND COLUMN_NAME = 'active_offset_key');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The foreign key on advance_id is served by uk_advance_report. MySQL refuses to drop that index while the
-- foreign key needs it (error 1553), so a plain index on advance_id is created first.
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD INDEX idx_cash_advance_adjustment_advance (advance_id)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND INDEX_NAME = 'idx_cash_advance_adjustment_advance');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- The old constraint forbade a second row per (advance, report), including after reversal. Drop it if present.
SET @sql = (SELECT IF(COUNT(*) > 0, 'ALTER TABLE cash_advance_adjustment DROP INDEX uk_advance_report', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND INDEX_NAME = 'uk_advance_report');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- One ACTIVE offset per advance and report. Reversed rows have a NULL key and are not constrained.
SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_adjustment ADD UNIQUE KEY uk_cash_advance_adjustment_active_offset (active_offset_key)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_adjustment' AND INDEX_NAME = 'uk_cash_advance_adjustment_active_offset');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
