# Issue: cash advances closed with no recorded settlement

**Status:** Open, under investigation
**Severity:** High. Closed advances with no offset or repayment mean the company's books may not match the money actually recovered.
**Found:** 2026-10-12, during a review of the settlement workflow.

## Summary

Nine cash advances are in status `CLOSED` with an outstanding balance of 0. None of them has a recorded
offset (`cash_advance_adjustment`) or repayment (`cash_advance_repayment`) that accounts for the amount. The
closures therefore can't be explained by the application's settlement paths, which write one of those rows every time.

| Advance (uuid prefix) | Amount | Last updated (`updated_at`) | Offsets | Repayments |
|---|---|---|---|---|
| 3c9e33f3 | 150 | 2026-09-23 06:58 | 0 | 0 |
| 55da49af | 50,000 | 2026-09-30 11:09 | 0 | 0 |
| 5bff5bac | 555 | 2026-09-23 06:56 | 0 | 0 |
| a0287b86 | 133 | 2026-09-22 11:16 | 0 | 0 |
| b0f389e8 | 333 | 2026-09-23 12:09 | 0 | 0 |
| bb94effa | 160 | 2026-09-21 11:20 | 0 | 0 |
| c4173e05 | 700 | 2026-09-23 06:56 | 0 | 0 |
| c7f73936 | 1,234 | 2026-09-23 08:55 | 0 | 0 |
| e9436a5e | 18,000 | 2026-10-06 07:29 | 0 | 0 |

## Evidence so far

- **Timing:** five of the nine were updated between 06:56 and 08:55 on 2026-09-23, which suggests a batch or bulk update.
- **Audit trail:** the `audit_log` rows for these advances end at `APPROVED` or `DISBURSED`. There is no closing entry.
- **Database objects:** `information_schema.EVENTS` has no rows, `SHOW TRIGGERS` has no rows for `cash_advance`, and no stored routine references `cash_advance`. Nothing in the database closes advances by itself.
- **Application code:** the only code paths that set `CLOSED` are repayment, offset, and report-approval recalculation. Each one writes an offset or repayment row, or an audit entry (the report-approval path wrote no audit entry before this issue was logged, and now does). None of these advances has a matching row.
- **Binary log:** enabled, but only three files exist (`binlog.004196` to `004198`), and each is under 250 bytes. The September events are probably purged. This has not been confirmed.

## Hypotheses (not yet tested)

1. A direct `UPDATE` on `cash_advance` by a person or an external script, possibly in the 2026-09-23 batch.
2. An older build of the application, which may have closed advances differently (the 2026-09-18 audit rows use `DISBURSED` as a status, which the current code does not write).
3. A data import or sync from another system that writes `status` and `outstanding_balance` directly.

## Next steps

1. Run `scripts/investigate_closed_advances.sql` (read-only) and record the results in this issue.
2. Confirm whether the binary log still covers 2026-09-21 to 2026-10-06. If not, check the backend console output and any sync or import logs for those dates.
3. Check the git history for changes to closure logic: `git log -S 'CLOSED' --since=2026-09-01 -- src/main/java`.
4. Ask the team who had write access to `cash_advance` on 2026-09-21 to 2026-10-06 and what they ran.
5. Decide how to correct the records. Do not reopen or change any advance until steps 1–4 are complete and the correction has been approved.

## Constraints

- No production data may be changed without explicit approval.
- Migrations V31–V33 will run automatically on the next backend start. Hold the restart until this issue is reviewed.
- The backfill script (`scripts/cash_advance_allocation_backfill.sql`) must not run until this issue is closed.

## Related work

- Balance rule and audit ledger: `CashAdvanceBalanceRules`, `CashAdvanceBalanceLedger`.
- Database guard against `CLOSED` with a balance: `V33__cash_advance_closed_requires_zero_balance.sql`.
- Note: the V33 guard does not catch these closures, because their balance is zero. A reconciliation check (offsets + repayments = amount) is needed for that.

## Security note

Shared login credentials for several users appeared in this conversation. They should be rotated.
