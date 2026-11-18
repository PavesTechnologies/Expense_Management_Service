-- Supplementary Expense Claims, part 3: AP payment request for a claim, and single use of a released receipt.
-- Additive only. No existing table row is changed.
-- Local MySQL only. NOT applied to the shared cloud database without explicit approval.

-- One payment request per claim (uk_supp_payment_claim). The request is created when Finance approves the claim.
-- It is paid only when AP records a confirmed payment with a bank reference that no other payment uses.
CREATE TABLE supplementary_payment (
    payment_id       BINARY(16)     NOT NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    claim_id         BINARY(16)     NOT NULL,
    amount           DECIMAL(19,4)  NOT NULL,
    currency_code    CHAR(3)        NOT NULL,
    status           VARCHAR(32)    NOT NULL,
    requested_by     VARCHAR(255)   NOT NULL,
    requested_at     DATETIME(6)    NOT NULL,
    paid_by          VARCHAR(255)   NULL,
    paid_at          DATETIME(6)    NULL,
    bank_reference   VARCHAR(100)   NULL,
    payment_method   VARCHAR(32)    NULL,
    payment_date     DATE           NULL,
    remarks          VARCHAR(500)   NULL,
    PRIMARY KEY (payment_id),
    CONSTRAINT uk_supp_payment_claim UNIQUE (claim_id),
    CONSTRAINT uk_supp_payment_reference UNIQUE (bank_reference),
    CONSTRAINT fk_supp_payment_claim FOREIGN KEY (claim_id) REFERENCES supplementary_expense_claim (claim_id),
    CONSTRAINT ck_supp_payment_amount CHECK (amount > 0),
    INDEX idx_supp_payment_status (status)
);

-- A released receipt may attach to one claim. consumed_at is set on attach and never cleared, so a receipt that was
-- used once cannot be reused without a new release. Rows are never deleted, so the release history is kept.
ALTER TABLE receipt_release
    ADD COLUMN consumed_at DATETIME(6) NULL;
