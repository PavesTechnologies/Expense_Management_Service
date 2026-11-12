-- Tax Phase 3: OCR tax evidence on the line and the tax validation tolerances as configuration.
-- Safe to re-run: the column add is guarded, and INSERT IGNORE keeps any value an admin already set
-- (config_key is unique).

-- How sure OCR was of the tax it read (0-1), copied with ocr_tax_amount when the receipt is
-- confirmed, so a low-confidence reading is ignored for comparison even after OCR re-runs.
SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'expense_line_item' AND COLUMN_NAME = 'ocr_tax_confidence') = 0,
               'ALTER TABLE expense_line_item ADD COLUMN ocr_tax_confidence DECIMAL(5, 4) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

INSERT IGNORE INTO system_configuration (config_id, config_key, config_value, data_type, description, created_at, updated_at)
VALUES
    (UUID_TO_BIN(UUID()), 'TAX_ROUNDING_TOLERANCE_MINOR_UNITS', '1', 'DECIMAL',
     'Entered vs calculated tax difference treated as rounding, in minor units per tax component (1 = 0.01 INR each).',
     NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'TAX_OCR_TOLERANCE_ABSOLUTE', '1.00', 'DECIMAL',
     'OCR vs entered tax difference still treated as a match, as an amount. The larger of this and the percent applies.',
     NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'TAX_OCR_TOLERANCE_PERCENT', '1', 'DECIMAL',
     'OCR vs entered tax difference still treated as a match, as a percent of the tax.',
     NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'TAX_OCR_MIN_CONFIDENCE', '80', 'DECIMAL',
     'OCR tax readings below this confidence (0-100) are ignored when validating a line''s tax.',
     NOW(6), NOW(6));
