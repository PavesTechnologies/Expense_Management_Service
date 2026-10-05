-- Unlike V17 (which added an index to a column ddl-auto=update had already created on a prior
-- run), this migration introduces brand-new columns AND their indexes in one pass — it cannot
-- rely on ddl-auto=update running first, because Flyway's flywayInitializer bean is a hard
-- dependency of entityManagerFactory (Hibernate), so Flyway always runs BEFORE ddl-auto=update
-- ever touches the schema, every startup. Column types below match this project's existing
-- Hibernate UUID mapping exactly (confirmed against project_cache.project_id and
-- finance_verification_review.gl_account_id_at_verification, both BINARY(16)).
ALTER TABLE project_cache
    ADD COLUMN pms_project_id BIGINT NULL,
    ADD COLUMN client_id BINARY(16) NULL;

ALTER TABLE expense_line_item
    ADD COLUMN resolved_client_id BINARY(16) NULL,
    ADD COLUMN resolved_client_name VARCHAR(255) NULL;

-- Unique index, not a unique constraint: MySQL treats multiple NULLs as distinct under a
-- unique index, so rows created before Epic 8 (via the legacy manual admin API,
-- ProjectCacheController) that have no pms_project_id remain valid.
CREATE UNIQUE INDEX idx_project_cache_pms_project_id ON project_cache (pms_project_id);

-- Supports the invoice-team handoff queue's client filter (InvoiceHandoffQueueController).
CREATE INDEX idx_expense_line_item_resolved_client_id ON expense_line_item (resolved_client_id);
