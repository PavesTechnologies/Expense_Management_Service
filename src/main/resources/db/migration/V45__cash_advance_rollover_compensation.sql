-- V45: Compensating reversal of a rollover after its request has been disbursed.
--
-- A compensation is a new carry-forward row that points back to the rollover it reverses. Nothing is deleted: the original
-- row becomes REVERSED and the compensation row carries the reason and both approvers.
-- Guarded, so databases where the column or index already exists are not affected. No existing rows are changed.

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE cash_advance_carry_forward ADD COLUMN reverses_carry_forward_id BINARY(16) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND COLUMN_NAME = 'reverses_carry_forward_id');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_cash_advance_carry_forward_reverses ON cash_advance_carry_forward (reverses_carry_forward_id)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance_carry_forward' AND INDEX_NAME = 'idx_cash_advance_carry_forward_reverses');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
