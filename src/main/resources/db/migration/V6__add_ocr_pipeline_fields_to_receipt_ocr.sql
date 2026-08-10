-- EP03-S3: extend receipt_ocr with the fields the real OCR pipeline needs
-- (tax amount, per-attempt processing status, failure reason). merchant_name,
-- receipt_date, amount, currency_code, confidence_score and processed_at already
-- exist and are unchanged. Additive only — no existing column changes type.
ALTER TABLE receipt_ocr
    ADD COLUMN tax_amount        DECIMAL(19,4) NULL AFTER currency_code,
    ADD COLUMN processing_status VARCHAR(30)   NOT NULL DEFAULT 'PROCESSING' AFTER confidence_score,
    ADD COLUMN failure_reason    VARCHAR(500)  NULL AFTER processing_status;

CREATE INDEX idx_receipt_ocr_processing_status ON receipt_ocr (processing_status);

-- Supports duplicate detection (vendor + amount + date) and "latest attempt for
-- this receipt" lookups without a full table scan.
CREATE INDEX idx_receipt_ocr_dup_lookup ON receipt_ocr (merchant_name, amount, receipt_date);
