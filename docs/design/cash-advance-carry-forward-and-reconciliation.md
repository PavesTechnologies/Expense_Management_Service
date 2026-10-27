# Cash advance carry-forward, Finance reconciliation and payroll recovery (design, blocked pending approvals)

Status: **design only. Not implemented.** No carry-forward, reconciliation, write-off or payroll code, schema or endpoint
exists. This document records the agreed rules (section 0), the safeguards that must exist before implementation
(sections 1 to 3), and the approvals still required (section 4). Implementation stays blocked until section 4 is complete.

## 0. Agreed rules (decision record)

| Area | Rule |
|---|---|
| Carry-forward: who proposes | An employee or a Finance user, with a reason. |
| Carry-forward: who approves | An authorized Finance approver **other than the requester**. |
| Carry-forward: amount | Partial allocations allowed. Several destinations allowed. The combined active allocations from one source must stay within its **verified source balance**. |
| Carry-forward: destination | Must be **approved** and **not yet disbursed**. |
| Carry-forward: deadlines | Does **not** clear overdue status or change any deadline. |
| Carry-forward: review cycles | Allocations that cross a review-cycle boundary are **blocked** until Finance confirms the cycle rules. |
| Reconciliation: periods | Monthly, quarterly, half-yearly and financial-year. Each period closes only after Finance approval. |
| Reconciliation: financial year | Start date **to be confirmed by Finance before implementation**. Not assumed here. |
| Reconciliation: balances | Expected balances are calculated from the movement ledger. Discrepancies are flagged. Balances are **never silently overwritten**. |
| Payroll deductions | Blocked until Finance and HR/Payroll approve limits, authorization, employee-exit handling, and the integration and confirmation contract. |
| Deadline overrides | Per-report. Advance-level expense window kept for separate advance-wide rules. Covered by tests (see section 1, safeguard 9). |

## 1. Advance-to-advance carry-forward (blocked until Finance confirms cycle rules)

### Model: `cash_advance_carry_forward`
One row per allocation from a source advance to a destination advance.

| Column | Type | Notes |
|---|---|---|
| `carry_forward_id` | UUID PK | |
| `source_advance_id` | UUID FK `cash_advance` | Balance is carried out of this advance. |
| `destination_advance_id` | UUID FK `cash_advance` | Balance is carried into this advance. |
| `employee_id` | VARCHAR | Both advances must belong to this employee. |
| `currency_code` | VARCHAR(3) | Both advances must share it. |
| `amount` | DECIMAL(19,4) | Positive. |
| `status` | VARCHAR | `PROPOSED` → `APPROVED` → `APPLIED`; `REJECTED` or `CANCELLED` from `PROPOSED`; `REVERSED` after `APPLIED`. |
| `reason` | TEXT | Required at proposal. |
| `requested_by`, `requested_at` | VARCHAR, DATETIME | The employee or Finance user who proposed it. |
| `approved_by`, `approved_at` | VARCHAR, DATETIME | Must differ from `requested_by`. |
| `applied_at` | DATETIME | When the balance moved. |
| `version` | BIGINT | Optimistic locking. |

Only `APPLIED` changes `cash_advance.outstanding_balance`, through `CashAdvanceBalanceLedger`, so each movement has an audit row.

### Safeguards (all required before implementation)
1. **Requester ≠ approver.** Checked in the service at approval, not only in the UI.
2. **Verified source balance.** The sum of active allocations from a source (`PROPOSED`, `APPROVED`, `APPLIED`) must not exceed
   its verified outstanding balance. "Verified" means the server's ledger balance, not a value typed by the user.
   Re-checked at approval and at apply.
3. **Destination eligibility at approval.** The destination must be `APPROVED` (not `DISBURSED`, not `SETTLED`, not `CLOSED`),
   belong to the same employee, and use the same currency.
4. **Multiple destinations.** Allowed. Each allocation is checked against the source total in safeguard 2.
5. **No circular allocation.** Before approving `A → B`, walk applied allocations flowing into `A` and reject if `B` is reached.
   Use a visited set.
6. **Cross-review-cycle block.** An allocation whose source and destination fall in different review cycles is refused
   until Finance confirms the cycle rules. The cycle rules are not defined in this repo's specification.
7. **No deadline bypass.** Applying a carry-forward does not clear or extend the source's overdue status or any report deadline.
   Overdue checks run as they do today.
8. **Lock order and idempotency.** Lock both advances `SELECT … FOR UPDATE` in ascending id order at approval. Approve and apply
   are idempotent on the carry-forward id.
9. **Deadline-override isolation (tested).** A per-report deadline override on one report does not change another report that
   shares the same advance. Covered by `ApprovalWorkflowServiceImplTest.aReportOverride_doesNotExtendAnotherReportLinkedToTheSameAdvance`.
   The advance-level expense window is unchanged.
10. **Reversal** writes a compensating ledger entry and never deletes a row.

## 2. Finance reconciliation (blocked until the financial-year start is confirmed)

### Purpose
A periodic check that each advance's stored balance equals its expected balance from the movement ledger, with every
difference flagged and explained before the period closes.

### Periods
Monthly, quarterly, half-yearly and financial-year. **The financial-year start is not assumed.** Finance confirms it before
implementation. Until then, financial-year periods cannot be created.

### Model
- `cash_advance_reconciliation_run`: `run_id`, `period_type` (MONTHLY | QUARTERLY | HALF_YEARLY | FINANCIAL_YEAR), `period_start`,
  `period_end`, `status` (`DRAFT` → `SUBMITTED` → `APPROVED` → `CLOSED`), `created_by`, `approved_by`, timestamps, `version`.
- `cash_advance_reconciliation_line`: `run_id`, `advance_id`, `opening_balance`, `disbursed_in_period`, `repaid_in_period`,
  `offset_in_period`, `carried_out_in_period`, `carried_in_in_period`, `expected_closing` (from the ledger), `stored_closing`,
  `discrepancy` (= stored − expected), `discrepancy_reason`, `resolution`, `resolved_by`.

### Workflow
1. **Snapshot.** Compute expected balances from the movement ledger for the period. Read-only. Nothing is written to `cash_advance`.
2. **Flag.** Every non-zero discrepancy is flagged on its line.
3. **Resolve.** Each resolution goes through its own rule (repayment, offset, carry-forward) with a reason. A line is never
   resolved by overwriting `stored_closing`.
4. **Approve and close.** Finance approval closes the period. A closed run is immutable. Re-running creates a new run.
5. **Audit.** Each status change writes an audit row.

### Balance source of truth
The movement ledger is the source for expected balances. `outstanding_balance` is a stored value that reconciliation
checks against the ledger. It is corrected only through a recorded movement, never by overwriting.

## 3. Payroll deductions (blocked)

**Write-offs are not part of this design.** The company has no write-off policy. Any pending amount is resolved through
carry-forward (section 1), a refund request with a one-day grace period, or a salary-recovery report sent to the CEO.

**Payroll deductions.** Not implemented. The UI option is blocked (the backend has no endpoint). Before any implementation,
Finance and HR/Payroll must approve:
- limits per pay cycle, authorization and consent;
- employee-exit handling (what happens to an open balance when the employee leaves);
- the integration and confirmation contract: interface, confirmation message, failure handling.

Until then, a deduction can be recorded only as a **request** in state `PENDING_PAYROLL`. It must not change the advance
balance, and it must never be shown as completed without payroll confirmation.

## 4. Approvals required before implementation

| # | Item | Owner |
|---|---|---|
| 1 | Review-cycle rules (needed for the cross-cycle block) | Finance |
| 2 | Financial-year start date | Finance |
| 4 | Payroll: limits, authorization, employee-exit handling, integration and confirmation contract | Finance and HR/Payroll |
| 5 | Destination statuses that accept a carry-in (`APPROVED` confirmed by the decision record) | Finance |

## 5. Order after approvals

1. Reconciliation read-only snapshot (no writes), once item 2 is confirmed.
2. Carry-forward with safeguards in section 1, behind a feature flag, once item 1 is confirmed.
3. Reconciliation resolution and approval.
5. Payroll integration, only after item 4.

Each step needs tests for the safeguard rejections (requester = approver, over-allocation, circular allocation, cross-cycle block,
ineligible destination), idempotent retry, lock order under concurrent calls, and an audit row for every balance change.
