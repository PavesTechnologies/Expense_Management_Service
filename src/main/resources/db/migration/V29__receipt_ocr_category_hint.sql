-- OCR category hint (TRAVEL, MEALS, ...) classified from the receipt text. Resolved to an active
-- expense_category at read time, so this stores the hint, not a category id.
--
-- Guarded by an information_schema lookup: ddl-auto=update may already have added the column
-- from the entity on a dev restart, and MySQL has no ADD COLUMN IF NOT EXISTS.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'receipt_ocr' AND COLUMN_NAME = 'category') = 0,
               'ALTER TABLE receipt_ocr ADD COLUMN category VARCHAR(32) NULL AFTER payment_method', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
