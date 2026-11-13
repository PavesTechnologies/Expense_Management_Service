-- V43: Supporting documents for cash advance requests.
--
-- Only metadata is stored here. The file itself lives in object storage under object_key. Removal is a soft delete
-- (deleted_at, deleted_by), so the history of what was attached is kept.
--
-- CREATE TABLE IF NOT EXISTS keeps this safe to run where the table already exists (for example, a database that was
-- built by hibernate ddl-auto). Existing rows in other tables are not touched.

CREATE TABLE IF NOT EXISTS cash_advance_document (
    document_id        BINARY(16)     NOT NULL PRIMARY KEY,
    advance_id         BINARY(16)     NOT NULL,
    employee_id        VARCHAR(255)   NOT NULL,
    category           VARCHAR(40)    NOT NULL,
    original_file_name VARCHAR(255)   NOT NULL,
    stored_file_name   VARCHAR(255)   NOT NULL,
    object_key         VARCHAR(512)   NOT NULL,
    content_type       VARCHAR(100)   NOT NULL,
    file_size_bytes    BIGINT         NOT NULL,
    uploaded_by        VARCHAR(255)   NOT NULL,
    uploaded_at        DATETIME(6)    NOT NULL,
    deleted_by         VARCHAR(255)   NULL,
    deleted_at         DATETIME(6)    NULL,
    CONSTRAINT uk_cash_advance_document_object_key UNIQUE (object_key),
    CONSTRAINT fk_cash_advance_document_advance FOREIGN KEY (advance_id) REFERENCES cash_advance (advance_id),
    INDEX idx_cash_advance_document_advance (advance_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
