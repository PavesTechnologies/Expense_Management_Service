-- Tax Phase 1 follow-up: widen text columns the tax feature writes to that ended up TINYTEXT
-- (255 bytes). @Lob with JPA's default length maps to TINYTEXT under ddl-auto=update (see the
-- note on CdcFailureLog), so audit_log could not hold the tax audit JSON, and a long tax-code
-- description or OCR component list would fail with "Data too long" under STRICT_ALL_TABLES.
-- The entities now declare columnDefinition = "LONGTEXT" so Hibernate agrees.
--
-- Each ALTER only runs if the column exists and is not already LONGTEXT (safe to re-run).

SET @ddl := IF((SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'audit_log' AND COLUMN_NAME = 'old_value') <> 'longtext',
               'ALTER TABLE audit_log MODIFY COLUMN old_value LONGTEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'audit_log' AND COLUMN_NAME = 'new_value') <> 'longtext',
               'ALTER TABLE audit_log MODIFY COLUMN new_value LONGTEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'tax_code' AND COLUMN_NAME = 'description') <> 'longtext',
               'ALTER TABLE tax_code MODIFY COLUMN description LONGTEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'receipt_ocr' AND COLUMN_NAME = 'tax_components') <> 'longtext',
               'ALTER TABLE receipt_ocr MODIFY COLUMN tax_components LONGTEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
