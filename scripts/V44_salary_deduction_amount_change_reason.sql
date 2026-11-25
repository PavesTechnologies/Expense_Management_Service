-- DRAFT - NOT APPLIED. Do not run against any shared or production database without approval.
-- Stores Finance's reason when a salary deduction is requested for less than the full outstanding balance.
-- Depends on V43 (salary_deduction_request). Hibernate ddl-auto=update also adds this column on startup.

ALTER TABLE salary_deduction_request
    ADD COLUMN amount_change_reason VARCHAR(500) NULL;
