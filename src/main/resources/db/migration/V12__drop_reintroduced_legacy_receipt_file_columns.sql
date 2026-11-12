-- V4 already dropped file_name/file_path/file_type once, but they have been reintroduced to the
-- live `receipt` table out-of-band a second time (confirmed via information_schema.COLUMNS:
-- file_name is VARCHAR NOT NULL with no default, file_path/file_type are nullable — the same
-- shape Hibernate's ddl-auto=update produces from the pre-S3-rework Receipt entity, which still
-- declares these three fields). Flyway's history shows V4 already succeeded, so it will never
-- reapply that DROP on its own. Because file_name is NOT NULL with no default, every
-- `uploadForLineItem`/`upload` INSERT has been failing with a DataIntegrityViolationException
-- ("Field 'file_name' doesn't have a default value") since the columns reappeared.
--
-- All 46 existing rows carry file_name = '' and file_path/file_type = NULL — dead data, already
-- fully superseded by original_file_name/stored_file_name/object_key/content_type (populated by
-- every real upload since V1). No backfill is needed before dropping them this time.
--
-- This only removes the symptom on this database; the reintroduction itself comes from something
-- external to this codebase (most likely another checkout still running the pre-S3-rework Receipt
-- entity with hibernate.ddl-auto=update against this same shared dev database) and needs to be
-- tracked down separately so it doesn't happen a third time.
ALTER TABLE receipt
    DROP COLUMN file_name,
    DROP COLUMN file_path,
    DROP COLUMN file_type;
