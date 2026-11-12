-- ============================================================================
-- V35: cash advance disbursement details and 30-day expense deadline
--
-- MANUAL RUN ONLY. Flyway is disabled in this project (spring.flyway.enabled=false)
-- and ddl-auto=update is on, so nothing here runs automatically.
--
-- Run on DEV first. Do not run on the shared database until dev has been checked.
--
-- Safe to re-run: every column is added only if it does not already exist.
-- Existing advances are NOT backfilled: disbursed_at stays NULL, so the 30-day
-- clock does not start for them (by design).
-- ============================================================================

-- disbursed_at: the moment Finance disbursed the advance. The 30-day window starts here.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursed_at') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursed_at DATETIME(6) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_mode: BANK_TRANSFER or CASH.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_mode') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_mode VARCHAR(20) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_reference: bank transaction reference (bank transfer only).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_reference') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_reference VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_payment_date: date the money was sent or handed over.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_payment_date') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_payment_date DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_acknowledged_by: who received the cash (cash only).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_acknowledged_by') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_acknowledged_by VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_proof_reference: number of the signed cash acknowledgment slip (cash only).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_proof_reference') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_proof_reference VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- expense_deadline_extended_until: Finance override. When set, it replaces disbursed_at + 30 days.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'expense_deadline_extended_until') = 0,
  'ALTER TABLE cash_advance ADD COLUMN expense_deadline_extended_until DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- expense_deadline_reopened: Finance override. When 1, the window is open regardless of date.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'expense_deadline_reopened') = 0,
  'ALTER TABLE cash_advance ADD COLUMN expense_deadline_reopened TINYINT(1) NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- deadline_reminder_sent_at: set once the 7-day reminder has gone out, so it is sent only once.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'deadline_reminder_sent_at') = 0,
  'ALTER TABLE cash_advance ADD COLUMN deadline_reminder_sent_at DATETIME(6) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Verification (read-only): the nine new-or-existing columns should all appear.
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cash_advance'
  AND column_name IN ('disbursed_at', 'disbursement_mode', 'disbursement_reference', 'disbursement_payment_date',
                      'disbursement_acknowledged_by', 'disbursement_proof_reference',
                      'expense_deadline_extended_until', 'expense_deadline_reopened', 'deadline_reminder_sent_at')
ORDER BY column_name;
