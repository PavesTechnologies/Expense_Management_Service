-- Tax Phase 4: when a line's tax last changed while its report was being corrected after a
-- submission. A tax-only change is not material for manager re-approval, but Finance must
-- re-verify that line: on resume, a VERIFIED Finance review older than this is reopened.
-- Guarded so it is safe to re-run.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_revised_at') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_revised_at DATETIME(6) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
