-- V46: Late and missing receipts attached to an expense after its report was approved, reimbursed or closed.
--
-- A late receipt is a new Receipt row linked to the original expense line. It never changes the line's amount, status or
-- payment, so the financial history stays as it was. Columns are added only when missing, so re-running is safe.
-- Existing rows keep attachment_kind = ORIGINAL and carry no late-receipt review data.

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN attachment_kind VARCHAR(20) NOT NULL DEFAULT ''ORIGINAL''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'attachment_kind');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN actual_receipt_date DATE NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'actual_receipt_date');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN late_reason VARCHAR(500) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'late_reason');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN verification_status VARCHAR(20) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'verification_status');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN verified_by VARCHAR(255) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'verified_by');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN verified_at DATETIME NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'verified_at');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'ALTER TABLE receipt ADD COLUMN verification_note VARCHAR(500) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND COLUMN_NAME = 'verification_note');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0, 'CREATE INDEX idx_receipt_attachment_review ON receipt (attachment_kind, verification_status)', 'SELECT 1') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt' AND INDEX_NAME = 'idx_receipt_attachment_review');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
