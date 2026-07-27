-- Converts the `receipt` table from ad-hoc file metadata to the Amazon S3-backed model:
-- file_name/file_path/file_type are superseded by original_file_name/stored_file_name/
-- object_key/content_type. Existing rows (if any) are backfilled before the legacy
-- columns are dropped, so this is safe to run against either an empty or populated table.
--
-- This is the first Flyway-managed migration in this project — every other table was
-- previously created ad hoc by `hibernate.ddl-auto=update` (see spring.flyway.baseline-*
-- in application.properties, which tells Flyway to treat that pre-existing schema as an
-- already-applied baseline rather than requiring a full historical migration set).

ALTER TABLE receipt
    ADD COLUMN original_file_name VARCHAR(255) NULL,
    ADD COLUMN stored_file_name VARCHAR(255) NULL,
    ADD COLUMN object_key VARCHAR(512) NULL,
    ADD COLUMN content_type VARCHAR(255) NULL;

UPDATE receipt
SET original_file_name = file_name,
    stored_file_name   = file_name,
    object_key          = file_path,
    content_type        = file_type
WHERE original_file_name IS NULL;

ALTER TABLE receipt
    MODIFY COLUMN original_file_name VARCHAR(255) NOT NULL,
    MODIFY COLUMN stored_file_name VARCHAR(255) NOT NULL,
    MODIFY COLUMN object_key VARCHAR(512) NOT NULL;

ALTER TABLE receipt
    DROP COLUMN file_name,
    DROP COLUMN file_path,
    DROP COLUMN file_type;
