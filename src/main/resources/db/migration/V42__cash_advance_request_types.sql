-- V42: Cash advance request types, calculation snapshot, employee justification, and the rejection reason.
--
-- Production runs without ddl-auto, so every column the code maps must exist through a migration. Each addition is
-- guarded, so databases where a column already exists are not affected. Existing rows are not changed: a NULL
-- request_type is read as COMPLETE_NEW, which is how requests behaved before this migration.
-- rejection_reason and deferred_reason are both mapped by the entity; deferred_reason was added by V40.

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN request_type VARCHAR(40) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'request_type');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN additional_amount_requested DECIMAL(19, 4) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'additional_amount_requested');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN outstanding_amount_snapshot DECIMAL(19, 4) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'outstanding_amount_snapshot');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN total_advance_allocation DECIMAL(19, 4) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'total_advance_allocation');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN employee_justification TEXT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'employee_justification');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN rejection_reason TEXT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'rejection_reason');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
