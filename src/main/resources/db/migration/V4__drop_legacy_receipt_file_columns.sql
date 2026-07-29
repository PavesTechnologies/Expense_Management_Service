-- V1 already dropped file_name/file_path/file_type in favor of original_file_name/
-- stored_file_name/object_key/content_type, but these three columns were re-added to the
-- live table out-of-band (bypassing Flyway) after V1 ran — Flyway's own history shows V1
-- succeeded, so it will never re-apply that DROP on its own. Since file_name is NOT NULL
-- with no default, every receipt upload's INSERT has been failing with a
-- DataIntegrityViolationException until these are removed again. Confirmed present via
-- direct inspection before writing this migration (see information_schema.COLUMNS) — a
-- plain DROP COLUMN (MySQL has no IF EXISTS clause for this, unlike MariaDB) is safe since
-- there is only one environment/database for this project.
ALTER TABLE receipt
    DROP COLUMN file_name,
    DROP COLUMN file_path,
    DROP COLUMN file_type;
