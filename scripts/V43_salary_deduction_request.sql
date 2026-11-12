-- DRAFT - NOT APPLIED. Do not run against any shared or production database without approval.
-- Adds the salary deduction request table, the payroll result columns on the recovery case, and the link from a
-- request to the automatic recovery case it was raised for. Depends on V42 (cash_advance_recovery_case, outbound_email).

CREATE TABLE salary_deduction_request (
    salary_deduction_request_id UUID          NOT NULL PRIMARY KEY,
    version                     BIGINT        NOT NULL DEFAULT 0,
    payroll_request_id          VARCHAR(32)   NOT NULL UNIQUE,
    advance_id                  UUID          NOT NULL,
    employee_id                 VARCHAR(255)  NOT NULL,
    requested_amount            DECIMAL(19,4) NOT NULL,
    payroll_month               VARCHAR(7)    NOT NULL,
    status                      VARCHAR(32)   NOT NULL,
    requested_by                VARCHAR(255)  NOT NULL,
    requested_at                TIMESTAMP     NOT NULL,
    outbound_email_id           UUID          NULL,
    sent_at                     TIMESTAMP     NULL,
    sent_to_payroll             BOOLEAN       NOT NULL DEFAULT FALSE,
    cancelled_at                TIMESTAMP     NULL,
    cancelled_by                VARCHAR(255)  NULL,
    cancellation_reason         VARCHAR(500)  NULL,
    outcome_recorded            BOOLEAN       NOT NULL DEFAULT FALSE,
    actual_amount_deducted      DECIMAL(19,4) NULL,
    payroll_month_processed     VARCHAR(7)    NULL,
    payroll_reference           VARCHAR(100)  NULL,
    evidence_type               VARCHAR(32)   NULL,
    evidence_reference          VARCHAR(512)  NULL,
    evidence_note               VARCHAR(500)  NULL,
    confirmed_at                TIMESTAMP     NULL,
    confirmed_by                VARCHAR(255)  NULL,
    recovery_case_id            UUID          NULL,
    CONSTRAINT fk_salary_deduction_advance FOREIGN KEY (advance_id) REFERENCES cash_advance (advance_id),
    CONSTRAINT fk_salary_deduction_recovery FOREIGN KEY (recovery_case_id) REFERENCES cash_advance_recovery_case (recovery_case_id)
);

CREATE INDEX idx_salary_deduction_advance ON salary_deduction_request (advance_id, status);

ALTER TABLE cash_advance_recovery_case
    ADD COLUMN resolution_note        VARCHAR(500)  NULL,
    ADD COLUMN payroll_actual_deducted DECIMAL(19,4) NULL,
    ADD COLUMN payroll_reference      VARCHAR(100)  NULL,
    ADD COLUMN payroll_recorded_at    TIMESTAMP     NULL,
    ADD COLUMN payroll_recorded_by    VARCHAR(255)  NULL;
