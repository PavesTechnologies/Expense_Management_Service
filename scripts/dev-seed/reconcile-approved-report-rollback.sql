-- DEV ONLY. Undoes reconcile-approved-report.sql: removes the adjustment and ledger row, and restores the advance and
-- report to their pre-reconciliation values. Matched by fixed ids. Run this before the advance rollback.

DELETE FROM audit_log WHERE audit_id = UNHEX('58684abfa5234e2cab2d0a33cc2d6755');

DELETE FROM cash_advance_adjustment WHERE adjustment_id = UNHEX('0f9a9263fbcb431a875bfa3680fc4970');

UPDATE cash_advance
SET outstanding_balance = 8000.0000, status = 'IN_PROGRESS', updated_at = NOW(6), version = version + 1
WHERE advance_id = UNHEX('7243315f85d4435694b38ca7239ffaa9');

UPDATE expense_report
SET reimbursable_amount = 6496.1500, payment_routing_status = 'APPROVED_FOR_PAYMENT', no_payment_due = b'0', updated_at = NOW(6), version = version + 1
WHERE report_id = UNHEX('b810824e44fb4996992caf819d55a802');
