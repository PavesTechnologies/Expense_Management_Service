-- ============================================================================
-- V42: cash advance recovery cases and the outbound email outbox
--
-- MANUAL RUN ONLY. Do NOT run until: (1) the V30/V31 history is reconciled, (2) the migration numbers are settled
-- (this version number is provisional), (3) a backup exists and the restore is verified, and (4) the owner gives
-- explicit confirmation for the named dev database. See docs/design/migration-plan-v38-v39.md.
--
-- Safe to re-run: each table is created only if missing. Requires cash_advance to exist.
-- ============================================================================

CREATE TABLE IF NOT EXISTS cash_advance_recovery_case (
  recovery_case_id         BINARY(16)    NOT NULL,
  version                  BIGINT        NOT NULL DEFAULT 0,
  advance_id               BINARY(16)    NOT NULL,
  employee_id              VARCHAR(255)  NOT NULL,
  amount_at_notice         DECIMAL(19,4) NOT NULL,
  status                   VARCHAR(32)   NOT NULL,
  notice_sent_at           DATETIME(6)   NOT NULL,
  notice_sent_by           VARCHAR(255)  NOT NULL,
  due_at                   DATETIME(6)   NOT NULL,
  resolved_at              DATETIME(6)   NULL,
  payroll_report_sent_at   DATETIME(6)   NULL,
  PRIMARY KEY (recovery_case_id),
  CONSTRAINT fk_recovery_case_advance FOREIGN KEY (advance_id) REFERENCES cash_advance (advance_id),
  INDEX idx_recovery_case_status_due (status, due_at),
  INDEX idx_recovery_case_advance (advance_id, status)
);

CREATE TABLE IF NOT EXISTS outbound_email (
  outbound_email_id  BINARY(16)    NOT NULL,
  to_address         VARCHAR(255)  NOT NULL,
  subject            VARCHAR(255)  NOT NULL,
  body               LONGTEXT      NOT NULL,
  email_type         VARCHAR(64)   NOT NULL,
  reference_id       VARCHAR(64)   NULL,
  status             VARCHAR(16)   NOT NULL,
  attempts           INT           NOT NULL DEFAULT 0,
  last_error         VARCHAR(500)  NULL,
  created_at         DATETIME(6)   NOT NULL,
  sent_at            DATETIME(6)   NULL,
  PRIMARY KEY (outbound_email_id),
  INDEX idx_outbound_email_status (status, created_at),
  INDEX idx_outbound_email_reference (email_type, reference_id)
);

-- Verification (read-only)
SELECT table_name, COUNT(*) AS columns_present
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('cash_advance_recovery_case', 'outbound_email')
GROUP BY table_name;
