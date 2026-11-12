-- Phase 3 of the Policy & Compliance Engine: introduces enforcementType (WARN/BLOCK) as a field
-- separate from severity (WARN/INFO) - severity is a signal-strength tier, enforcement is whether
-- a violation can actually stop submission. Every existing rule/violation backfills to WARN, so
-- nothing that passes submission today starts blocking tomorrow purely from this migration.
--
-- PRECONDITION, same as V9: `policy_rule.enforcement_type` and `policy_violation.enforcement_type`
-- are NOT created by this script - they are additive columns left to `hibernate.ddl-auto=update`,
-- picking up the new PolicyRule.enforcementType / PolicyViolation.enforcementType fields. This
-- migration must only run after an app start has already created them (mirroring V3's and V9's
-- add-column-then-backfill sequencing). Verify via information_schema.COLUMNS against the target
-- database before running this in any environment where that prior deploy hasn't happened yet.

UPDATE policy_rule
SET enforcement_type = 'WARN'
WHERE enforcement_type IS NULL;

UPDATE policy_violation
SET enforcement_type = 'WARN'
WHERE enforcement_type IS NULL;

ALTER TABLE policy_rule
    MODIFY COLUMN enforcement_type VARCHAR(255) NOT NULL;

ALTER TABLE policy_violation
    MODIFY COLUMN enforcement_type VARCHAR(255) NOT NULL;
