-- EP04 Cash Advance Management: Phase 2-4 Schema Enhancements
-- Adds: @Version for concurrency control, settlement metadata, payment tracking, and idempotency keys

-- Add version column for optimistic locking to cash_advance table
ALTER TABLE cash_advance ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Add settlement metadata columns to cash_advance table
ALTER TABLE cash_advance ADD COLUMN approved_amount DECIMAL(19, 4) NULL;
ALTER TABLE cash_advance ADD COLUMN settlement_status VARCHAR(255) NULL;
ALTER TABLE cash_advance ADD COLUMN settled_at TIMESTAMP NULL;
ALTER TABLE cash_advance ADD COLUMN settled_by VARCHAR(255) NULL;

-- Add version column for optimistic locking to cash_advance_adjustment table
ALTER TABLE cash_advance_adjustment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Add payment tracking columns to cash_advance_adjustment table
ALTER TABLE cash_advance_adjustment ADD COLUMN payment_method VARCHAR(255) NULL;
ALTER TABLE cash_advance_adjustment ADD COLUMN transaction_reference VARCHAR(255) NULL;
ALTER TABLE cash_advance_adjustment ADD COLUMN payment_date TIMESTAMP NULL;
ALTER TABLE cash_advance_adjustment ADD COLUMN disbursement_status VARCHAR(255) NULL;
ALTER TABLE cash_advance_adjustment ADD COLUMN idempotency_key VARCHAR(255) UNIQUE NULL;

-- Add unique constraint on (advance_id, report_id) to prevent duplicate settlements
ALTER TABLE cash_advance_adjustment ADD CONSTRAINT uk_advance_report UNIQUE (advance_id, report_id);

-- Add version column for optimistic locking to cash_advance_repayment table
ALTER TABLE cash_advance_repayment ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
