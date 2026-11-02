-- DEV ONLY. Reconciles report EXP-2026-2027-54D2B88D against its seeded advance, the way
-- ApprovalWorkflowServiceImpl.autoOffsetCashAdvance does on approval. That code ran after this report was approved,
-- so this applies it once. The runner checks the expected pre-change values before anything is written.
-- Rollback: reconcile-approved-report-rollback.sql.

-- 1. The adjustment: the approved total (6496.15) against advance "[SEED] Audit trip advance".
INSERT INTO cash_advance_adjustment (adjustment_id, version, advance_id, report_id, adjusted_amount, adjusted_by, adjusted_at, idempotency_key)
VALUES (UNHEX('0f9a9263fbcb431a875bfa3680fc4970'), 0,
    UNHEX('7243315f85d4435694b38ca7239ffaa9'), UNHEX('b810824e44fb4996992caf819d55a802'),
    6496.1500, 'SYSTEM', NOW(6), 'auto-offset-b810824e-44fb-4996-992c-af819d55a802');

-- 2. The advance: 8000 - 6496.15 = 1503.85, still open, so SETTLEMENT_PENDING.
UPDATE cash_advance
SET outstanding_balance = 1503.8500, status = 'SETTLEMENT_PENDING', updated_at = NOW(6), version = version + 1
WHERE advance_id = UNHEX('7243315f85d4435694b38ca7239ffaa9') AND outstanding_balance = 8000.0000;

-- 3. The report: nothing left for AP to pay, so it leaves the payment queue.
UPDATE expense_report
SET reimbursable_amount = 0.0000, payment_routing_status = 'NONE', no_payment_due = b'1', updated_at = NOW(6), version = version + 1
WHERE report_id = UNHEX('b810824e44fb4996992caf819d55a802') AND reimbursable_amount = 6496.1500;

-- 4. Ledger entry for the advance balance change.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, new_value, old_value, performed_at, performed_by, reason, source)
VALUES (UNHEX('58684abfa5234e2cab2d0a33cc2d6755'), 'AUTO_RECONCILED_ON_APPROVAL',
    UNHEX('7243315f85d4435694b38ca7239ffaa9'), 'CashAdvance',
    '{"outstandingBalance":"1503.85","status":"SETTLEMENT_PENDING"}', '{"outstandingBalance":"8000.00","status":"IN_PROGRESS"}',
    NOW(6), 'SYSTEM', 'SEED reconciliation of EXP-2026-2027-54D2B88D', 'SEED');
