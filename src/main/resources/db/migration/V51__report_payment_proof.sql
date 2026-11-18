-- Proof of payment for a report, attached when AP confirms the payment. Additive: one nullable column.
-- Existing reports keep NULL; they were paid before proof was required and are not changed.
-- Local MySQL only. NOT applied to the shared cloud database without explicit approval.

ALTER TABLE expense_report
    ADD COLUMN payment_proof_reference VARCHAR(512) NULL;
