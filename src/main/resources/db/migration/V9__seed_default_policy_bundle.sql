-- Phase 1 of the Policy & Compliance Engine bundle model: seeds exactly one "Default Policy" and
-- backfills every existing policy_rule row onto it, then locks the new FK as NOT NULL.
--
-- Since every employee is currently subject to identical rules (no per-employee assignment model
-- exists yet), treating the entire existing policy_rule set as one bundle is not a simplification
-- for this migration's sake - it is an exact description of what the system already does. A single
-- policy_assignment row of type DEFAULT is also seeded here so the future assignment resolver
-- (Phase 2) always has exactly one fallback to resolve to - never a zero-assignment state.
--
-- PRECONDITION, same as this project's established ddl-auto/Flyway split (see V1, V5): the `policy`
-- and `policy_assignment` tables and the `policy_rule.policy_bundle_id` column are NOT created by
-- this script - they are additive schema left to `hibernate.ddl-auto=update`, picking up the new
-- Policy/PolicyAssignment entities and PolicyRule.policy field. This migration must only run after
-- an app start has already created them (mirroring V3's real add-column-then-backfill sequencing).
-- Verify via information_schema.COLUMNS/TABLES against the target database before running this in
-- any environment where that prior deploy hasn't happened yet.

INSERT INTO policy (policy_id, policy_name, description, status, version, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()),
       'Default Policy',
       'Seeded by the Policy bundle migration - represents the single rule set every employee was subject to before per-employee assignment existed.',
       'ACTIVE',
       0,
       NOW(),
       NOW()
WHERE NOT EXISTS (SELECT 1 FROM policy WHERE policy_name = 'Default Policy');

UPDATE policy_rule
SET policy_bundle_id = (SELECT policy_id FROM policy WHERE policy_name = 'Default Policy' LIMIT 1)
WHERE policy_bundle_id IS NULL;

INSERT INTO policy_assignment (assignment_id, assignment_type, employee_id, policy_id, status, version, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()),
       'DEFAULT',
       NULL,
       (SELECT policy_id FROM policy WHERE policy_name = 'Default Policy' LIMIT 1),
       'ACTIVE',
       0,
       NOW(),
       NOW()
WHERE NOT EXISTS (SELECT 1 FROM policy_assignment WHERE assignment_type = 'DEFAULT');

ALTER TABLE policy_rule
    MODIFY COLUMN policy_bundle_id BINARY(16) NOT NULL;
