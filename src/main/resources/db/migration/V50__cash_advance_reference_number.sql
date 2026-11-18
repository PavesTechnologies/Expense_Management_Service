-- Cash advance reference number, in the same form as an expense report number: ADV-<fiscal year>-<8 hex characters>.
-- Additive: one nullable column, then a backfill of existing rows from their own id and creation date.
-- Fiscal year runs April to March (fiscal year 2026-2027 starts 1 Apr 2026), matching ExpenseReportServiceImpl.
-- Local MySQL only. NOT applied to the shared cloud database without explicit approval.

ALTER TABLE cash_advance
    ADD COLUMN advance_number VARCHAR(32) NULL,
    ADD CONSTRAINT uk_cash_advance_number UNIQUE (advance_number);

UPDATE cash_advance
SET advance_number = CONCAT(
        'ADV-',
        CASE WHEN MONTH(created_at) >= 4
             THEN CONCAT(YEAR(created_at), '-', YEAR(created_at) + 1)
             ELSE CONCAT(YEAR(created_at) - 1, '-', YEAR(created_at))
        END,
        '-',
        UPPER(SUBSTRING(HEX(advance_id), 1, 8)))
WHERE advance_number IS NULL;
