-- Expense splitting & duplicate receipt prevention.
--
-- 1. expense_line_item.allocation_reason: why a line is charged (directly or via its splits) to a
--    cost center outside the employee's home department. Required at submission only in that case.
-- 2. budget_encumbrance.cost_center_id: the cost center a reservation is held against. Unsplit lines
--    now reserve against their own cost center (not always the report's), so an unbudgeted remainder
--    row needs to record it. Nullable, no backfill: older rows mean the split's cost center, or the
--    report's for a null-split row.
--
-- Every statement is guarded so it is safe to re-run and safe when Hibernate (ddl-auto=update)
-- added the column first.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'allocation_reason') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN allocation_reason VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'budget_encumbrance' AND COLUMN_NAME = 'cost_center_id') = 0,
               'ALTER TABLE budget_encumbrance ADD COLUMN cost_center_id BINARY(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'budget_encumbrance' AND COLUMN_NAME = 'cost_center_id') = 0,
               'CREATE INDEX idx_budget_encumbrance_cost_center ON budget_encumbrance (cost_center_id)', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
