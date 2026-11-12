-- Record which fiscal year's budget a cash advance was charged to at final approval.
-- Offsets and repayments must move money in that same budget row, even if they happen in a later fiscal year.
--
-- Guarded by an information_schema lookup, as in V31–V33: ddl-auto=update may already have added the
-- column from the CashAdvance entity, and MySQL has no ADD COLUMN IF NOT EXISTS.
-- NULL means the advance was never charged to a budget (legacy or unbudgeted), so no budget movement applies.

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cash_advance') = 1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME = 'cash_advance' AND COLUMN_NAME = 'budget_fiscal_year') = 0,
    'ALTER TABLE cash_advance ADD COLUMN budget_fiscal_year VARCHAR(10) NULL',
    'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
