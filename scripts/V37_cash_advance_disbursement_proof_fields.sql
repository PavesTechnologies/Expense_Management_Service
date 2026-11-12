-- ============================================================================
-- V37: extra disbursement proof fields on cash_advance
--
-- MANUAL RUN ONLY. Flyway is disabled in this project (spring.flyway.enabled=false) and ddl-auto=update
-- is on. Run on DEV first. Do not run on the shared database without approval.
--
-- Safe to re-run: every column is added only if it does not already exist.
-- Requires V35 (disbursed_at, disbursement_* columns) to have run first.
-- ============================================================================

-- disbursement_transfer_details: bank and beneficiary account (bank transfer).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_transfer_details') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_transfer_details TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_payee_upi_id: payee UPI ID (UPI).
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_payee_upi_id') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_payee_upi_id VARCHAR(255) NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- disbursement_confirmation_note: optional note from Finance.
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'cash_advance' AND column_name = 'disbursement_confirmation_note') = 0,
  'ALTER TABLE cash_advance ADD COLUMN disbursement_confirmation_note TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Verification (read-only)
SELECT column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'cash_advance'
  AND column_name IN ('disbursement_transfer_details', 'disbursement_payee_upi_id', 'disbursement_confirmation_note')
ORDER BY column_name;
