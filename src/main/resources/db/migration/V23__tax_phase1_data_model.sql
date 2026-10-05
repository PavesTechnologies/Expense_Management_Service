-- Tax Phase 1: data model for server-calculated, snapshotted expense tax (India GST first).
-- Everything is additive: new tables, new nullable (or defaulted) columns, deterministic backfills.
--
-- Safe to re-run and safe against tables/columns Hibernate (ddl-auto=update) may already have
-- created on a devtools hot restart (the V20 incident): CREATE TABLE IF NOT EXISTS, and every
-- ADD COLUMN / CREATE INDEX is guarded by an information_schema lookup, since MySQL has no
-- ADD COLUMN IF NOT EXISTS. Backfills only touch rows that have not been backfilled yet.

-- ---------------------------------------------------------------------------------------------
-- 1. tax_code: country / region and a percentage-based input tax credit.
-- ---------------------------------------------------------------------------------------------
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'tax_code' AND COLUMN_NAME = 'country_code') = 0,
               'ALTER TABLE tax_code ADD COLUMN country_code VARCHAR(2) NOT NULL DEFAULT ''IN''', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'tax_code' AND COLUMN_NAME = 'region_code') = 0,
               'ALTER TABLE tax_code ADD COLUMN region_code VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'tax_code' AND COLUMN_NAME = 'itc_recoverable_percent') = 0,
               'ALTER TABLE tax_code ADD COLUMN itc_recoverable_percent DECIMAL(5, 2) NOT NULL DEFAULT 0.00', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- itc_eligible stays as a derived flag (percent > 0). Existing eligible codes become fully
-- recoverable; Finance reviews the percentages afterwards (blocked / partial credits).
UPDATE tax_code SET itc_recoverable_percent = 100.00
WHERE itc_eligible = b'1' AND itc_recoverable_percent = 0.00;

-- ---------------------------------------------------------------------------------------------
-- 2. tax_code_component: the levied parts of a code (CGST 9 + SGST 9, IGST 18, cess ...).
--    tax_code.rate_percent becomes the derived sum of these.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tax_code_component (
    component_id   BINARY(16)    NOT NULL,
    tax_code_id    BINARY(16)    NOT NULL,
    component_code VARCHAR(16)   NOT NULL,
    label          VARCHAR(100)  NOT NULL,
    rate_percent   DECIMAL(5, 2) NOT NULL,
    sequence       INT           NOT NULL,
    gl_account_id  BINARY(16)    NULL,
    created_at     DATETIME(6)   NULL,
    updated_at     DATETIME(6)   NULL,
    PRIMARY KEY (component_id),
    CONSTRAINT uk_tax_code_component_code UNIQUE (tax_code_id, component_code),
    CONSTRAINT fk_tax_code_component_tax_code FOREIGN KEY (tax_code_id) REFERENCES tax_code (tax_code_id),
    CONSTRAINT fk_tax_code_component_gl_account FOREIGN KEY (gl_account_id) REFERENCES gl_account (gl_account_id)
);

-- One component row set per existing code that has none yet. CGST_SGST splits the rate in half.
INSERT INTO tax_code_component (component_id, tax_code_id, component_code, label, rate_percent, sequence, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), t.tax_code_id, 'CGST', CONCAT('CGST ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(t.rate_percent / 2 AS CHAR))), '%'),
       t.rate_percent / 2, 1, NOW(6), NOW(6)
FROM tax_code t
WHERE t.tax_type = 'CGST_SGST'
  AND NOT EXISTS (SELECT 1 FROM tax_code_component c WHERE c.tax_code_id = t.tax_code_id);

INSERT INTO tax_code_component (component_id, tax_code_id, component_code, label, rate_percent, sequence, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), t.tax_code_id, 'SGST', CONCAT('SGST ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(t.rate_percent / 2 AS CHAR))), '%'),
       t.rate_percent - t.rate_percent / 2, 2, NOW(6), NOW(6)
FROM tax_code t
WHERE t.tax_type = 'CGST_SGST'
  AND NOT EXISTS (SELECT 1 FROM tax_code_component c WHERE c.tax_code_id = t.tax_code_id AND c.component_code = 'SGST');

INSERT INTO tax_code_component (component_id, tax_code_id, component_code, label, rate_percent, sequence, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), t.tax_code_id, t.tax_type, CONCAT(t.tax_type, ' ', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(t.rate_percent AS CHAR))), '%'),
       t.rate_percent, 1, NOW(6), NOW(6)
FROM tax_code t
WHERE t.tax_type IN ('IGST', 'VAT')
  AND NOT EXISTS (SELECT 1 FROM tax_code_component c WHERE c.tax_code_id = t.tax_code_id);

-- ---------------------------------------------------------------------------------------------
-- 3. expense_category_tax_mapping: which code applies to a category on a date. Replaces the
--    expense_category.tax_code string, which is kept in sync (the code in effect today) until a
--    later release drops it.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expense_category_tax_mapping (
    mapping_id     BINARY(16)   NOT NULL,
    category_id    BINARY(16)   NOT NULL,
    tax_code_id    BINARY(16)   NOT NULL,
    effective_from DATE         NOT NULL,
    effective_to   DATE         NULL,
    created_by     VARCHAR(255) NULL,
    created_at     DATETIME(6)  NULL,
    updated_at     DATETIME(6)  NULL,
    PRIMARY KEY (mapping_id),
    CONSTRAINT fk_category_tax_mapping_category FOREIGN KEY (category_id) REFERENCES expense_category (category_id),
    CONSTRAINT fk_category_tax_mapping_tax_code FOREIGN KEY (tax_code_id) REFERENCES tax_code (tax_code_id)
);
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_category_tax_mapping' AND INDEX_NAME = 'idx_category_tax_mapping_category_from') = 0,
               'CREATE INDEX idx_category_tax_mapping_category_from ON expense_category_tax_mapping (category_id, effective_from)', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- Seed from the existing string reference (open-ended, from when the code itself took effect).
INSERT INTO expense_category_tax_mapping (mapping_id, category_id, tax_code_id, effective_from, created_by, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), c.category_id, t.tax_code_id, t.effective_from, 'MIGRATION_V23', NOW(6), NOW(6)
FROM expense_category c
JOIN tax_code t ON UPPER(t.tax_code) = UPPER(TRIM(c.tax_code))
WHERE c.tax_code IS NOT NULL AND TRIM(c.tax_code) <> ''
  AND NOT EXISTS (SELECT 1 FROM expense_category_tax_mapping m WHERE m.category_id = c.category_id);

-- ---------------------------------------------------------------------------------------------
-- 4. expense_line_item: the tax snapshot (frozen at submission from Phase 2 on).
-- ---------------------------------------------------------------------------------------------
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_code_id') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_code_id BINARY(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_code') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_code VARCHAR(50) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_type') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_type VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_rate_percent') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_rate_percent DECIMAL(5, 2) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_treatment') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_treatment VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'calculated_tax_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN calculated_tax_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_source') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_source VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_override_reason') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_override_reason VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'itc_recoverable_percent') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN itc_recoverable_percent DECIMAL(5, 2) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'recoverable_tax_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN recoverable_tax_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'base_tax_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN base_tax_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'base_net_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN base_net_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'base_recoverable_tax_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN base_recoverable_tax_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'ocr_tax_amount') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN ocr_tax_amount DECIMAL(19, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_validation_status') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_validation_status VARCHAR(32) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_validation_reasons') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_validation_reasons VARCHAR(255) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'tax_snapshot_at') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN tax_snapshot_at DATETIME(6) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- Every existing line is LEGACY: no code, no components, no inferred rate, no ITC claimed.
-- Base values are deterministic: base_tax = round(tax x rate) in base-currency minor units
-- (2 dp for INR), and base_net is derived so base_net + base_tax = base_amount exactly.
UPDATE expense_line_item
SET tax_source                  = 'LEGACY',
    tax_validation_status       = 'LEGACY',
    tax_treatment               = 'INCLUSIVE',
    itc_recoverable_percent     = 0.00,
    recoverable_tax_amount      = 0.0000,
    base_recoverable_tax_amount = 0.0000,
    base_tax_amount             = ROUND(COALESCE(tax_amount, 0) * COALESCE(exchange_rate, 1), 2),
    base_net_amount             = COALESCE(base_amount, amount) - ROUND(COALESCE(tax_amount, 0) * COALESCE(exchange_rate, 1), 2)
WHERE tax_source IS NULL;

-- ---------------------------------------------------------------------------------------------
-- 5. expense_line_tax_component: snapshotted per-component amounts of a line.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expense_line_tax_component (
    line_tax_component_id BINARY(16)     NOT NULL,
    line_item_id          BINARY(16)     NOT NULL,
    component_code        VARCHAR(16)    NOT NULL,
    label                 VARCHAR(100)   NOT NULL,
    rate_percent          DECIMAL(5, 2)  NULL,
    tax_amount            DECIMAL(19, 4) NOT NULL,
    base_tax_amount       DECIMAL(19, 4) NULL,
    sequence              INT            NOT NULL,
    source                VARCHAR(16)    NOT NULL,
    created_at            DATETIME(6)    NULL,
    PRIMARY KEY (line_tax_component_id),
    CONSTRAINT fk_line_tax_component_line_item FOREIGN KEY (line_item_id) REFERENCES expense_line_item (line_item_id)
);
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_tax_component' AND INDEX_NAME = 'idx_line_tax_component_line_item') = 0,
               'CREATE INDEX idx_line_tax_component_line_item ON expense_line_tax_component (line_item_id)', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------------------------------------
-- 6. receipt_ocr: OCR tax evidence (components as JSON text - read with the receipt, never joined).
-- ---------------------------------------------------------------------------------------------
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'receipt_ocr' AND COLUMN_NAME = 'tax_components') = 0,
               'ALTER TABLE receipt_ocr ADD COLUMN tax_components LONGTEXT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'receipt_ocr' AND COLUMN_NAME = 'supplier_gstin') = 0,
               'ALTER TABLE receipt_ocr ADD COLUMN supplier_gstin VARCHAR(15) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'receipt_ocr' AND COLUMN_NAME = 'tax_confidence') = 0,
               'ALTER TABLE receipt_ocr ADD COLUMN tax_confidence DECIMAL(5, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

-- ---------------------------------------------------------------------------------------------
-- 7. audit_log: why a change was made and who/what made it (EMPLOYEE, OCR, FINANCE, ADMIN, SYSTEM).
-- ---------------------------------------------------------------------------------------------
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'audit_log' AND COLUMN_NAME = 'reason') = 0,
               'ALTER TABLE audit_log ADD COLUMN reason VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'audit_log' AND COLUMN_NAME = 'source') = 0,
               'ALTER TABLE audit_log ADD COLUMN source VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
