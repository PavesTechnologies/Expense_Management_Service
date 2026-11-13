-- V41: AP payment batches and per-report allocations (Decision 3).
--
-- One bank reference covers several expense reports ONLY through a recorded batch. Each allocation is the amount of one
-- report paid by that batch, so every report keeps its own payment history. A report is marked PAYMENT_COMPLETED only
-- by the allocation that settles its remaining payable amount.
--
-- Uniqueness is enforced by the database:
--   * one batch per bank reference (uk_ap_payment_batch_reference)
--   * one allocation per batch and report (uk_ap_allocation_batch_report)
-- The tables are new, so this script is not guarded: it fails loudly if they already exist with other content.
-- Rollback: DROP TABLE ap_payment_allocation; DROP TABLE ap_payment_batch; (no existing data is changed).

CREATE TABLE ap_payment_batch (
    batch_id          BINARY(16)     NOT NULL PRIMARY KEY,
    version           BIGINT         NOT NULL DEFAULT 0,
    bank_reference    VARCHAR(100)   NOT NULL,
    payment_method    VARCHAR(30)    NOT NULL,
    payment_date      DATE           NOT NULL,
    total_amount      DECIMAL(19, 4) NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    failure_reason    VARCHAR(500)   NULL,
    remarks           VARCHAR(500)   NULL,
    recorded_by       VARCHAR(255)   NOT NULL,
    recorded_at       DATETIME(6)    NOT NULL,
    CONSTRAINT uk_ap_payment_batch_reference UNIQUE (bank_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ap_payment_allocation (
    allocation_id     BINARY(16)     NOT NULL PRIMARY KEY,
    version           BIGINT         NOT NULL DEFAULT 0,
    batch_id          BINARY(16)     NOT NULL,
    report_id         BINARY(16)     NOT NULL,
    amount            DECIMAL(19, 4) NOT NULL,
    payable_before    DECIMAL(19, 4) NOT NULL,
    payable_after     DECIMAL(19, 4) NOT NULL,
    CONSTRAINT uk_ap_allocation_batch_report UNIQUE (batch_id, report_id),
    CONSTRAINT fk_ap_allocation_batch FOREIGN KEY (batch_id) REFERENCES ap_payment_batch (batch_id),
    CONSTRAINT fk_ap_allocation_report FOREIGN KEY (report_id) REFERENCES expense_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
