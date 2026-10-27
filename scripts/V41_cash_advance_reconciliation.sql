-- ============================================================================
-- V41: cash advance reconciliation runs and lines
--
-- MANUAL RUN ONLY. Do NOT run until: (1) the V30/V31 history is reconciled, (2) the migration numbers are settled
-- (this version number is provisional), (3) a backup exists and the restore is verified, and (4) the owner gives
-- explicit confirmation for the named dev database. See docs/design/migration-plan-v38-v39.md.
--
-- Safe to re-run: each table is created only if missing. Requires cash_advance to exist.
-- Financial-year periods need expense.finance-year-start-month; no column is needed for that.
-- ============================================================================

CREATE TABLE IF NOT EXISTS cash_advance_reconciliation_run (
  reconciliation_run_id BINARY(16)    NOT NULL,
  version               BIGINT        NOT NULL DEFAULT 0,
  period_type           VARCHAR(32)   NOT NULL,
  period_start          DATE          NOT NULL,
  period_end            DATE          NOT NULL,
  status                VARCHAR(16)   NOT NULL,
  created_by            VARCHAR(255)  NOT NULL,
  created_at            DATETIME(6)   NOT NULL,
  submitted_by          VARCHAR(255)  NULL,
  submitted_at          DATETIME(6)   NULL,
  approved_by           VARCHAR(255)  NULL,
  approved_at           DATETIME(6)   NULL,
  closed_by             VARCHAR(255)  NULL,
  closed_at             DATETIME(6)   NULL,
  PRIMARY KEY (reconciliation_run_id),
  INDEX idx_recon_run_period (period_type, period_start, status)
);

CREATE TABLE IF NOT EXISTS cash_advance_reconciliation_line (
  reconciliation_line_id BINARY(16)    NOT NULL,
  version                BIGINT        NOT NULL DEFAULT 0,
  reconciliation_run_id  BINARY(16)    NOT NULL,
  advance_id             BINARY(16)    NOT NULL,
  employee_id            VARCHAR(255)  NULL,
  expected_closing       DECIMAL(19,4) NOT NULL,
  stored_closing         DECIMAL(19,4) NOT NULL,
  discrepancy            DECIMAL(19,4) NOT NULL,
  flagged                BIT(1)        NOT NULL DEFAULT b'0',
  later_movements        INT           NOT NULL DEFAULT 0,
  resolution_note        VARCHAR(500)  NULL,
  resolved_by            VARCHAR(255)  NULL,
  resolved_at            DATETIME(6)   NULL,
  PRIMARY KEY (reconciliation_line_id),
  CONSTRAINT fk_recon_line_run FOREIGN KEY (reconciliation_run_id) REFERENCES cash_advance_reconciliation_run (reconciliation_run_id),
  CONSTRAINT fk_recon_line_advance FOREIGN KEY (advance_id) REFERENCES cash_advance (advance_id),
  INDEX idx_recon_line_run (reconciliation_run_id)
);

-- Verification (read-only)
SELECT table_name, COUNT(*) AS columns_present
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cash_advance_reconciliation_run', 'cash_advance_reconciliation_line')
GROUP BY table_name;
