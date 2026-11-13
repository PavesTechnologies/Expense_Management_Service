-- V44: Rollover of outstanding balances into a new cash advance (Cash Advance + Outstanding).
--
-- authorized_rollover_amount: the total balance Finance authorised to roll into this request. Null until approved.
-- Carry-forward rows get the overdue-exception fields: a rollover from an overdue advance is only ever created with a
-- Finance executive's authorisation, a reason, and the amount.
-- Each addition is guarded, so databases where a column already exists are not affected. No existing rows are changed.

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance ADD COLUMN authorized_rollover_amount DECIMAL(19, 4) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'authorized_rollover_amount');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_carry_forward ADD COLUMN authorization_type VARCHAR(30) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND COLUMN_NAME = 'authorization_type');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_carry_forward ADD COLUMN exception_justification TEXT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND COLUMN_NAME = 'exception_justification');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_carry_forward ADD COLUMN exception_authorized_by VARCHAR(255) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND COLUMN_NAME = 'exception_authorized_by');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_carry_forward ADD COLUMN exception_authorized_at DATETIME(6) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND COLUMN_NAME = 'exception_authorized_at');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
