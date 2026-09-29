-- Receipt.fileHash has been mapped on the entity since before `receipt` became fully
-- Flyway-managed, so the column already exists (created by hibernate.ddl-auto=update).
-- This migration only adds the index needed for ReceiptRepository.findByFileHash's
-- exact-file-reuse lookup (advisory-only duplicate detection, see ReceiptServiceImpl) —
-- deliberately NOT unique: reuse across employees/reports must be surfaced, not rejected.
CREATE INDEX idx_receipt_file_hash ON receipt (file_hash);
