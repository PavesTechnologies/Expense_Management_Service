-- Supplementary Expense Claims, part 2: receipt and AP payment links.
-- Additive only. Existing receipts and AP allocations keep NULL in the new columns and behave exactly as before.
-- Local MySQL only. NOT applied to the shared cloud database without explicit approval.

-- Each receipt row points at no more than one supplementary claim item, because it has one column. Several receipts
-- may belong to one item. Two concurrent attaches of the same receipt are serialised in the service with a row lock.
-- Detaching (claim rejected or cancelled) sets the column back to NULL so the receipt can be released and used again.
ALTER TABLE receipt
    ADD COLUMN supplementary_claim_item_id BINARY(16) NULL,
    ADD INDEX idx_receipt_supp_claim_item (supplementary_claim_item_id),
    ADD CONSTRAINT fk_receipt_supp_claim_item FOREIGN KEY (supplementary_claim_item_id)
        REFERENCES supplementary_claim_item (claim_item_id) ON DELETE SET NULL;

-- A supplementary claim is paid through the existing AP batch allocation model. Each claim may have at most one
-- allocation, which prevents a second payment request for the same claim.
ALTER TABLE ap_payment_allocation
    ADD COLUMN supplementary_claim_id BINARY(16) NULL,
    ADD CONSTRAINT uk_ap_alloc_supp_claim UNIQUE (supplementary_claim_id),
    ADD CONSTRAINT fk_ap_alloc_supp_claim FOREIGN KEY (supplementary_claim_id)
        REFERENCES supplementary_expense_claim (claim_id);
