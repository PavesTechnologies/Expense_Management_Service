-- EP03-S4: Receipt Upload / OCR redesign. Receipt now belongs directly to an
-- ExpenseReport and carries its own employee_id, so a receipt can exist (and OCR
-- can run) before any ExpenseLineItem is created. line_item_id becomes nullable —
-- it is populated only once the employee confirms/links a line item.

ALTER TABLE receipt
    ADD COLUMN report_id    BINARY(16) NULL AFTER receipt_id,
    ADD COLUMN employee_id  VARCHAR(255) NULL AFTER report_id;

-- Backfill both new columns for any existing rows from the current line_item_id chain.
UPDATE receipt r
    JOIN expense_line_item eli ON r.line_item_id = eli.line_item_id
    JOIN expense_report er ON eli.report_id = er.report_id
SET r.report_id = eli.report_id,
    r.employee_id = er.employee_id
WHERE r.report_id IS NULL;

ALTER TABLE receipt
    MODIFY COLUMN report_id BINARY(16) NOT NULL,
    MODIFY COLUMN employee_id VARCHAR(255) NOT NULL,
    MODIFY COLUMN line_item_id BINARY(16) NULL,
    ADD CONSTRAINT fk_receipt_report FOREIGN KEY (report_id) REFERENCES expense_report (report_id);

CREATE INDEX idx_receipt_report_id ON receipt (report_id);

-- Rename the shared OCR status vocabulary to match the richer receipt lifecycle
-- (UPLOADED / PROCESSING / OCR_COMPLETED / REVIEW_PENDING / VERIFIED / SUBMITTED /
-- FAILED -> RETRY_AVAILABLE). Existing data only, safe on an empty table too.
UPDATE receipt SET ocr_status = 'UPLOADED' WHERE ocr_status = 'QUEUED';
UPDATE receipt SET ocr_status = 'OCR_COMPLETED' WHERE ocr_status = 'COMPLETED';
UPDATE receipt SET ocr_status = 'REVIEW_PENDING' WHERE ocr_status = 'UNDER_REVIEW';
UPDATE receipt SET ocr_status = 'VERIFIED' WHERE ocr_status = 'CONFIRMED';
UPDATE receipt SET ocr_status = 'RETRY_AVAILABLE' WHERE ocr_status = 'FAILED';

UPDATE receipt_ocr SET processing_status = 'OCR_COMPLETED' WHERE processing_status = 'COMPLETED';

-- Track how a line item was created (manual entry vs. confirmed from OCR).
ALTER TABLE expense_line_item
    ADD COLUMN created_by VARCHAR(20) NOT NULL DEFAULT 'MANUAL' AFTER line_status;

-- OCR engine/version/timing metadata, tracked per attempt for support/analytics.
ALTER TABLE receipt_ocr
    ADD COLUMN processing_duration_ms BIGINT NULL AFTER processed_at,
    ADD COLUMN ocr_engine VARCHAR(50) NULL AFTER processing_duration_ms,
    ADD COLUMN ocr_version VARCHAR(50) NULL AFTER ocr_engine,
    ADD COLUMN invoice_number VARCHAR(255) NULL AFTER currency_code;
