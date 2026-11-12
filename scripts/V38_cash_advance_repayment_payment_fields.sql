-- ============================================================================
-- V38: payment confirmation fields on cash_advance_repayment
--
-- MANUAL RUN ONLY. Flyway is disabled in this project (spring.flyway.enabled=false) and ddl-auto=update
-- is on. Run on DEV first. Do not run on the shared database without approval.
--
-- Safe to re-run: every column is added only if it does not already exist.
-- Requires the cash_advance_repayment table (V30) to exist.
-- Not yet ordered against the V35/V36/V37 files in src/main/resources/db/migration; see the migration
-- reconciliation notes before choosing the final version number.
-- ============================================================================

-- payment_mode: CASH, BANK_TRANSFER or UPI.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'payment_mode') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN payment_mode VARCHAR(32) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- received_by_name: person who received the cash (CASH).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'received_by_name') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN received_by_name VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- transfer_date: date of the bank or UPI transfer.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'transfer_date') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN transfer_date DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- transfer_details: bank and beneficiary account (BANK_TRANSFER).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'transfer_details') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN transfer_details TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- payee_upi_id: payee UPI ID (UPI).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'payee_upi_id') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN payee_upi_id VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- proof_reference: storage key of the cash acknowledgment or UPI screenshot.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'proof_reference') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN proof_reference VARCHAR(512) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- confirmation_note: optional note from Finance.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment' AND column_name = 'confirmation_note') = 0,
  'ALTER TABLE cash_advance_repayment ADD COLUMN confirmation_note TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Verification (read-only)
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cash_advance_repayment'
  AND column_name IN ('payment_mode', 'received_by_name', 'transfer_date', 'transfer_details',
                      'payee_upi_id', 'proof_reference', 'confirmation_note')
ORDER BY column_name;
