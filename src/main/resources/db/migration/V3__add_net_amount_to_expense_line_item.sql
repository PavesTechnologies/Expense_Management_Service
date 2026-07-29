-- EP02-S4: net_amount is derived server-side as amount - COALESCE(tax_amount, 0) and
-- persisted so reporting/export queries don't need to recompute it. Backfill existing rows
-- before enforcing NOT NULL, consistent with V2's approach to evolving this table.
ALTER TABLE expense_line_item
    ADD COLUMN net_amount DECIMAL(19,4) NULL AFTER tax_amount;

UPDATE expense_line_item
SET net_amount = amount - COALESCE(tax_amount, 0)
WHERE net_amount IS NULL;

ALTER TABLE expense_line_item
    MODIFY COLUMN net_amount DECIMAL(19,4) NOT NULL;
