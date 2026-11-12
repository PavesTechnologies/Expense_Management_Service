-- ============================================================================
-- V40: cash advance carry-forward (allocation table and carried-in balance)
--
-- MANUAL RUN ONLY. Do NOT run until: (1) the V30/V31 history is reconciled, (2) the migration numbers are settled
-- (this version number is provisional), (3) a backup exists and the restore is verified, and (4) the owner gives
-- explicit confirmation for the named dev database. See docs/design/migration-plan-v38-v39.md.
--
-- Safe to re-run: the column is added only if missing; the table is created only if missing.
-- Requires cash_advance to exist.
-- ============================================================================

-- carried_in_amount: balance carried into this advance from earlier advances. NULL reads as zero.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'carried_in_amount') = 0,
  'ALTER TABLE cash_advance ADD COLUMN carried_in_amount DECIMAL(19,4) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cash_advance_carry_forward: one row per allocation. Rows are never deleted; reversal is a status change.
CREATE TABLE IF NOT EXISTS cash_advance_carry_forward (
  carry_forward_id      BINARY(16)     NOT NULL,
  version               BIGINT         NOT NULL DEFAULT 0,
  source_advance_id     BINARY(16)     NOT NULL,
  destination_advance_id BINARY(16)    NOT NULL,
  employee_id           VARCHAR(255)   NOT NULL,
  currency_code         VARCHAR(3)     NOT NULL,
  amount                DECIMAL(19,4)  NOT NULL,
  status                VARCHAR(32)    NOT NULL,
  reason                VARCHAR(500)   NOT NULL,
  idempotency_key       VARCHAR(255)   NULL,
  requested_by          VARCHAR(255)   NOT NULL,
  requested_at          DATETIME(6)    NOT NULL,
  approved_by           VARCHAR(255)   NULL,
  approved_at           DATETIME(6)    NULL,
  applied_at            DATETIME(6)    NULL,
  rejected_by           VARCHAR(255)   NULL,
  rejected_at           DATETIME(6)    NULL,
  decision_reason       VARCHAR(500)   NULL,
  reversed_by           VARCHAR(255)   NULL,
  reversed_at           DATETIME(6)    NULL,
  reversal_reason       VARCHAR(500)   NULL,
  PRIMARY KEY (carry_forward_id),
  CONSTRAINT fk_cf_source FOREIGN KEY (source_advance_id) REFERENCES cash_advance (advance_id),
  CONSTRAINT fk_cf_destination FOREIGN KEY (destination_advance_id) REFERENCES cash_advance (advance_id),
  CONSTRAINT uk_cf_idempotency_key UNIQUE (idempotency_key),
  INDEX idx_cf_source_status (source_advance_id, status),
  INDEX idx_cf_destination_status (destination_advance_id, status)
);

-- Verification (read-only)
SELECT table_name, column_name, column_type
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'cash_advance' AND column_name = 'carried_in_amount')
    OR table_name = 'cash_advance_carry_forward')
ORDER BY table_name, column_name;
