-- Supplementary Expense Claims: missing expenses on a settled report, submitted as a separate claim.
-- UUID columns are BINARY(16), matching how Hibernate stores UUIDs in this schema (verified against expense_report).
-- Additive only: no existing table is altered by this file. Receipt and AP links are added in V48.
-- NOT applied to the shared cloud database. Applied locally first; shared apply needs explicit approval.

CREATE TABLE supplementary_expense_claim (
    claim_id                 BINARY(16)      NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,
    claim_number             VARCHAR(32)   NOT NULL,
    original_report_id       BINARY(16)      NOT NULL,
    employee_id              VARCHAR(255)  NOT NULL,
    created_by               VARCHAR(255)  NOT NULL,
    created_on_behalf        BIT(1)        NOT NULL DEFAULT b'0',
    creation_reason          VARCHAR(500)  NULL,
    employee_confirmed_at    DATETIME(6)   NULL,
    late_reason_code         VARCHAR(40)   NOT NULL,
    late_reason_text         TEXT          NULL,
    total_amount             DECIMAL(19,4) NOT NULL,
    currency_code            CHAR(3)       NOT NULL,
    original_cash_advance_id BINARY(16)      NULL,
    status                   VARCHAR(32)   NOT NULL,
    submitted_at             DATETIME(6)   NULL,
    settled_at               DATETIME(6)   NULL,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (claim_id),
    CONSTRAINT uk_supp_claim_number UNIQUE (claim_number),
    CONSTRAINT fk_supp_claim_report FOREIGN KEY (original_report_id) REFERENCES expense_report (report_id),
    CONSTRAINT ck_supp_claim_total CHECK (total_amount >= 0),
    INDEX idx_supp_claim_report (original_report_id),
    INDEX idx_supp_claim_employee_status (employee_id, status),
    INDEX idx_supp_claim_status (status)
);

CREATE TABLE supplementary_claim_item (
    claim_item_id           BINARY(16)      NOT NULL,
    claim_id                BINARY(16)      NOT NULL,
    expense_date            DATE          NOT NULL,
    category_id             BINARY(16)      NOT NULL,
    merchant_name           VARCHAR(255)  NULL,
    description             VARCHAR(500)  NULL,
    amount                  DECIMAL(19,4) NOT NULL,
    currency_code           CHAR(3)       NOT NULL,
    employee_payment_method VARCHAR(32)   NULL,
    notes                   VARCHAR(500)  NULL,
    PRIMARY KEY (claim_item_id),
    CONSTRAINT fk_supp_item_claim FOREIGN KEY (claim_id) REFERENCES supplementary_expense_claim (claim_id) ON DELETE CASCADE,
    CONSTRAINT ck_supp_item_amount CHECK (amount > 0),
    INDEX idx_supp_item_claim (claim_id)
);

CREATE TABLE supplementary_claim_history (
    history_id      BINARY(16)     NOT NULL,
    claim_id        BINARY(16)     NOT NULL,
    actor_id        VARCHAR(255) NOT NULL,
    actor_role      VARCHAR(64)  NULL,
    action          VARCHAR(64)  NOT NULL,
    previous_status VARCHAR(32)  NULL,
    new_status      VARCHAR(32)  NULL,
    comment         TEXT         NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (history_id),
    CONSTRAINT fk_supp_history_claim FOREIGN KEY (claim_id) REFERENCES supplementary_expense_claim (claim_id),
    INDEX idx_supp_history_claim (claim_id, created_at)
);

CREATE TABLE receipt_release (
    release_id       BINARY(16)     NOT NULL,
    receipt_id       BINARY(16)     NOT NULL,
    requested_by     VARCHAR(255) NOT NULL,
    request_reason   VARCHAR(500) NOT NULL,
    requested_at     DATETIME(6)  NOT NULL,
    decided_by       VARCHAR(255) NULL,
    decision_reason  VARCHAR(500) NULL,
    decided_at       DATETIME(6)  NULL,
    status           VARCHAR(32)  NOT NULL,
    PRIMARY KEY (release_id),
    CONSTRAINT ck_release_two_people CHECK (decided_by IS NULL OR decided_by <> requested_by),
    INDEX idx_receipt_release_receipt (receipt_id, status)
);
