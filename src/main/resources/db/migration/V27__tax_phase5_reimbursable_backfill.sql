-- Tax Phase 5: reimbursable_amount was never computed (always 0). It is now gross (total_amount,
-- base currency) minus the report's cash advance adjustments, never below 0 - recoverable tax never
-- reduces what the employee is paid (BR-TAX-013). Backfill every report still at 0 / NULL;
-- re-running only touches rows that are still unset.
UPDATE expense_report r
SET r.reimbursable_amount = GREATEST(0,
        COALESCE(r.total_amount, 0)
        - COALESCE((SELECT SUM(a.adjusted_amount) FROM cash_advance_adjustment a WHERE a.report_id = r.report_id), 0))
WHERE (r.reimbursable_amount IS NULL OR r.reimbursable_amount = 0)
  AND COALESCE(r.total_amount, 0) > 0;
