-- Link an expense report to the cash advance it settles.
--
-- Guarded by an information_schema lookup, as in V31: ddl-auto=update may already have added the column
-- from the ExpenseReport entity, and MySQL has no ADD COLUMN IF NOT EXISTS.
--
-- Type assumption: cash_advance.advance_id is BINARY(16), Hibernate 6's default for UUID on MySQL.
-- Confirm with SHOW CREATE TABLE cash_advance; if it is CHAR(36), change BINARY(16) below to CHAR(36).
-- The foreign key itself is created by ddl-auto from the entity, so it is not added here.

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'expense_report') = 1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'cash_advance_id') = 0,
    'ALTER TABLE expense_report ADD COLUMN cash_advance_id BINARY(16) NULL',
    'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
