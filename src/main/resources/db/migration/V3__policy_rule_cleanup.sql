-- EP05 reshapes the previously-inert `policy_rule` table: `rule_type` and `severity` become real
-- enums (PolicyRuleType / PolicySeverity) instead of unvalidated free text, and the unused `action`
-- column is dropped now that this system is advisory-only (there is no action to take on a warning).
--
-- Nothing outside PolicyRule's own CRUD ever read this table before EP05, so there is no
-- application-level backward-compatibility concern — only data hygiene: any pre-existing row whose
-- rule_type/severity doesn't match the new enum's constant names is neutralised here rather than
-- left to throw on read once @Enumerated(EnumType.STRING) is in effect. rule_type is nullable
-- specifically so a neutralised row degrades to "PolicyEvaluator skips this rule and logs a
-- warning" rather than an exception, matching this epic's never-throw rule.
--
-- The new `policy_violation` table and the new `created_at`/`updated_at` columns on `policy_rule`
-- are additive and are left to `hibernate.ddl-auto=update` to create, consistent with how every
-- other new table/column in this project has been introduced (see V1's note: Flyway here is used
-- for drops and data changes ddl-auto can't safely perform, not for new schema).

UPDATE policy_rule
SET rule_type = NULL,
    status    = 'INACTIVE'
WHERE rule_type IS NOT NULL
  AND rule_type NOT IN ('AMOUNT_LIMIT', 'RECEIPT_REQUIRED', 'BACKDATED_DAYS', 'MISSING_DESCRIPTION', 'DUPLICATE_EXPENSE');

UPDATE policy_rule
SET severity = 'WARN'
WHERE severity IS NULL
   OR severity NOT IN ('WARN', 'INFO');

ALTER TABLE policy_rule
    DROP COLUMN action;
