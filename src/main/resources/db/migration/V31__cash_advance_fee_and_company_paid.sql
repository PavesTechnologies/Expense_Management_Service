-- Cash advance fee tracking: budget column for the fee allocation, and the company-paid flag on
-- expense reports (so a system-generated fee report never enters the employee reimbursement or AP
-- payment pipeline).
--
-- Every ADD COLUMN here is guarded by an information_schema lookup: ddl-auto=update creates the same
-- columns from the entities on a dev restart, and MySQL has no ADD COLUMN IF NOT EXISTS. The table
-- check matters on a fresh database, where Flyway can run before Hibernate creates the table.
--
-- The cash_advance_fee table and its UNIQUE (advance_id) constraint are declared on the
-- CashAdvanceFee entity and created by ddl-auto=update, following this project's convention for
-- new tables. This file deliberately does not create them, so the constraint and column types
-- always match the entity.
--
-- The one-off backfill of cash_advance_allocated for already-approved advances is NOT run here:
-- it reads that column, which may not exist yet when Flyway runs. See
-- scripts/cash_advance_allocation_backfill.sql.

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cost_center_budget') = 1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME = 'cost_center_budget' AND COLUMN_NAME = 'cash_advance_fee_allocated') = 0,
    'ALTER TABLE cost_center_budget ADD COLUMN cash_advance_fee_allocated DECIMAL(19,4) NULL',
    'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'expense_report') = 1
    AND (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
         AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'company_paid') = 0,
    'ALTER TABLE expense_report ADD COLUMN company_paid BIT(1) NOT NULL DEFAULT b''0''',
    'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
