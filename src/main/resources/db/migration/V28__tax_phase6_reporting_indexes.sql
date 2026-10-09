-- Tax Phase 6: indexes for tax reporting (grouping by tax code, filtering by expense date).
-- Guarded so it is safe to re-run.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND INDEX_NAME = 'idx_expense_line_item_tax_code_id') = 0,
               'CREATE INDEX idx_expense_line_item_tax_code_id ON expense_line_item (tax_code_id)', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND INDEX_NAME = 'idx_expense_line_item_expense_date') = 0,
               'CREATE INDEX idx_expense_line_item_expense_date ON expense_line_item (expense_date)', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
