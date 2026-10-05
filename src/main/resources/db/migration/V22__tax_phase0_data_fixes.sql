-- Tax Phase 0: data fixes found by the tax audit, applied before any tax feature work.
-- Every statement is idempotent: safe on a database where the fix is already in place.

-- 1. Blank category tax codes. Legacy category saves stored '' instead of NULL; '' means "no tax
--    code" everywhere, and the category service already stores NULL for new blanks.
UPDATE expense_category
SET tax_code = NULL
WHERE tax_code IS NOT NULL AND TRIM(tax_code) = '';

-- 2. Missing IGST18 seed row (seeded by V20 but absent from the dev database). INSERT IGNORE keeps
--    any existing row with this code untouched (unique key uk_tax_code_tax_code).
INSERT IGNORE INTO tax_code (tax_code_id, tax_code, tax_name, tax_type, rate_percent, itc_eligible,
                             description, effective_from, status, created_at, updated_at)
VALUES (UUID_TO_BIN(UUID()), 'IGST18', 'IGST 18%', 'IGST', 18.00, b'0',
        'Inter-state purchase (e.g. flights, out-of-state hotels)', '2017-07-01', 'ACTIVE', NOW(6), NOW(6));

-- 3. Duplicate unique index on tax_code.tax_code. Hibernate (ddl-auto=update) added an
--    auto-named copy of V20's uk_tax_code_tax_code from the entity's unnamed @UniqueConstraint,
--    which is now named to match. Drop any unique index on exactly (tax_code) other than ours;
--    its generated name differs per database, so it is looked up rather than hard-coded.
SET @dup_index := (
    SELECT s.INDEX_NAME
    FROM information_schema.STATISTICS s
    WHERE s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'tax_code'
      AND s.NON_UNIQUE = 0
      AND s.INDEX_NAME NOT IN ('PRIMARY', 'uk_tax_code_tax_code')
      AND s.COLUMN_NAME = 'tax_code'
      AND (SELECT COUNT(*) FROM information_schema.STATISTICS s2
           WHERE s2.TABLE_SCHEMA = s.TABLE_SCHEMA AND s2.TABLE_NAME = s.TABLE_NAME
             AND s2.INDEX_NAME = s.INDEX_NAME) = 1
    LIMIT 1);
SET @drop_sql := IF(@dup_index IS NULL, 'DO 0', CONCAT('ALTER TABLE tax_code DROP INDEX `', @dup_index, '`'));
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;
