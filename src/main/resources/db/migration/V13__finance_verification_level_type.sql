-- Finance Verification (EP07): approval_level.level_type / approval_level_instance.level_type
-- distinguish a normal Manager-review level from a Finance Verification level. Every level/level
-- instance configured or materialized before this feature existed backfills to APPROVAL, so no
-- existing flow's behavior changes - a flow with no Finance level still goes
-- PENDING_APPROVAL -> APPROVED exactly as before.
--
-- PRECONDITION, same sequencing as V10__add_policy_enforcement_type.sql: `approval_level.level_type`
-- and `approval_level_instance.level_type` are NOT created by this script - they are additive
-- columns left to `hibernate.ddl-auto=update`, picking up the new ApprovalLevel.levelType /
-- ApprovalLevelInstance.levelType fields. This migration must only run after an app start has
-- already created them. Verify via information_schema.COLUMNS against the target database before
-- running this in any environment where that prior deploy hasn't happened yet.

UPDATE approval_level
SET level_type = 'APPROVAL'
WHERE level_type IS NULL;

UPDATE approval_level_instance
SET level_type = 'APPROVAL'
WHERE level_type IS NULL;

ALTER TABLE approval_level
    MODIFY COLUMN level_type VARCHAR(255) NOT NULL;

ALTER TABLE approval_level_instance
    MODIFY COLUMN level_type VARCHAR(255) NOT NULL;
