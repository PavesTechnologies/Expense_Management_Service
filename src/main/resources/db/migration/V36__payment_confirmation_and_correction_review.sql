-- AP payment confirmation, settled-without-payment, and the correction / re-review workflow.
--
-- Columns (Hibernate ddl-auto=update adds these too; each is guarded so this is safe either way):
--   expense_report.payment_reference / payment_method / payment_date / paid_amount / payment_remarks:
--       what AP recorded when confirming the actual payment (a report shows as paid only then).
--   expense_report.no_payment_due: approved with nothing owed (fully offset by a cash advance or
--       company-paid) - never routed to AP, never shown as paid.
--   expense_line_item.revised_at: edited after submission; already-given reviews reopen on resubmit.
--   finance_verification_review.query_category, verification_query.category: why Finance asked for
--       a correction (e.g. TAX_MISMATCH).
--
-- Widening (Hibernate never alters an existing column): review comments and Finance query text were
-- created as TINYTEXT (255 bytes) by @Lob without a column definition, so a longer correction
-- request failed to save. Widened to TEXT only where still TINYTEXT.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'payment_reference') = 0,
               'ALTER TABLE expense_report ADD COLUMN payment_reference VARCHAR(100) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'payment_method') = 0,
               'ALTER TABLE expense_report ADD COLUMN payment_method VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'payment_date') = 0,
               'ALTER TABLE expense_report ADD COLUMN payment_date DATE NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'paid_amount') = 0,
               'ALTER TABLE expense_report ADD COLUMN paid_amount DECIMAL(19,4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'payment_remarks') = 0,
               'ALTER TABLE expense_report ADD COLUMN payment_remarks VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_report' AND COLUMN_NAME = 'no_payment_due') = 0,
               'ALTER TABLE expense_report ADD COLUMN no_payment_due BIT(1) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'revised_at') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN revised_at DATETIME(6) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'finance_verification_review' AND COLUMN_NAME = 'query_category') = 0,
               'ALTER TABLE finance_verification_review ADD COLUMN query_category VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'verification_query' AND COLUMN_NAME = 'category') = 0,
               'ALTER TABLE verification_query ADD COLUMN category VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- Widen TINYTEXT text columns to TEXT (only while still TINYTEXT; keeps nullability).
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'verification_query' AND COLUMN_NAME = 'query_text' AND DATA_TYPE = 'tinytext') = 1,
               'ALTER TABLE verification_query MODIFY COLUMN query_text TEXT NOT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'verification_query' AND COLUMN_NAME = 'employee_response' AND DATA_TYPE = 'tinytext') = 1,
               'ALTER TABLE verification_query MODIFY COLUMN employee_response TEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'finance_verification_review' AND COLUMN_NAME = 'comment' AND DATA_TYPE = 'tinytext') = 1,
               'ALTER TABLE finance_verification_review MODIFY COLUMN comment TEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'approval_line_item_review' AND COLUMN_NAME = 'comment' AND DATA_TYPE = 'tinytext') = 1,
               'ALTER TABLE approval_line_item_review MODIFY COLUMN comment TEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
