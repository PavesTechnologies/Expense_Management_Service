-- Tax ITC tracking: the supplier GSTIN of the invoice a line's input tax credit rests on. Copied
-- from the receipt's OCR when the receipt is confirmed (so it survives OCR re-runs), and set or
-- corrected by Finance while verifying. Nullable, no backfill: existing lines get it on their next
-- receipt confirmation or from Finance.
-- Guarded so it is safe to re-run and safe when Hibernate (ddl-auto=update) added the column first.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'supplier_gstin') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN supplier_gstin VARCHAR(15) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
