# Role-based dashboards: Milestone A audit

Scope: audit only. No source, API, migration, data or workflow was changed for this audit.
Status labels: **Implemented and verified** (tested), **Implemented but not verified** (code only),
**Partially implemented**, **Missing**, **Requires business decision**.

## 1. Role to dashboard mapping

| Role (backend `@PreAuthorize`) | Endpoint | Frontend view (`DashboardPage.jsx` `VIEWS`) | Status |
|---|---|---|---|
| any authenticated user | `GET /xms/dashboard/employee` | Expenses (employee) | Implemented but not verified (no service test found for `employee()`) |
| `MANAGER`, `REPORTING_MANAGER` | `GET /xms/dashboard/manager` | Approvals | Implemented but not verified |
| `FINANCE_EXECUTIVE`, `FINANCE` | `GET /xms/dashboard/finance` | Finance | Implemented but not verified |
| `AP_EXECUTIVE` | `GET /xms/dashboard/ap` | AP Payments | Implemented but not verified |
| `ADMIN`, `SUPER_ADMIN` | `GET /xms/dashboard/admin` | Organization | Implemented but not verified |

Findings:
- The four role dashboards exist. Role gating is enforced in the backend, and the frontend follows `hasRole`.
- There are **no request parameters** on any dashboard endpoint: no period, department, cost-centre, currency or employee filter. Status: **Missing**.
- There are **no drill-down endpoints**. Each card has no list of the records behind it. Status: **Missing**.
- The frontend has no card-level links to records. The only navigation is the attention and activity lists.

## 2. Card to database mapping (current KPIs)

Source: `DashboardServiceImpl.java`. Each KPI is named by its key.

### Employee (`employee()`)
| KPI | Source | Amount basis | Status |
|---|---|---|---|
| `claimed` | sum of stage totals for the employee's reports | gross report total (`baseAmountsByReport`) | Implemented but not verified |
| `draft`, `action`, `approvals`, `finance`, `settled` | report status via `employeeStage()` | gross totals | Implemented but not verified |
| `payment` ("awaiting payment") | reports with routing `APPROVED_FOR_PAYMENT` | **gross**, not net payable | Partially implemented (wrong basis: see risk R3) |
| `paid` | reports with routing `PAYMENT_COMPLETED` | `paidAmount` | Implemented but not verified |
| `advanceBalance` | `CashAdvanceBalanceCalculator.rawOutstandingFor` over open advances (added this session) | authoritative calculation | Implemented and verified by the calculator tests and the drift MySQL test; dashboard wiring not tested |

### Manager (`manager()`)
| KPI | Source | Status |
|---|---|---|
| `pending` | reports `PENDING_APPROVAL` for the team plus cash advances from `getMyApprovals` | Implemented but not verified |
| `pendingValue` | sum of claim amounts awaiting the manager | Implemented but not verified |
| `overdue` | reports past SLA | Implemented but not verified |
| `approved30`, `sentBack30` | audit-trail counts, last 30 days | Implemented but not verified |
| team spend, "you hold" | team reports; open advance balance | Partially implemented (team spend is gross; not split by recommendation) |

Missing for managers: recommended amount, full / reduced / set-off / deferred / rejected counts, employee aggregate exposure, previous advance outstanding per request. Status: **Missing**.

### Finance (`finance()`)
| KPI | Source | Status |
|---|---|---|
| `toVerify`, `queueValue` | active `ApprovalLevelInstance` at the finance level | Implemented but not verified |
| `queries` | open queries | Implemented but not verified |
| `verified` | `FinanceVerificationReview` this month | Implemented but not verified |
| `exceptions` | policy violations on the queue | Implemented but not verified |

Missing for Finance (no cash advance figures at all): requested, manager-recommended, finance-approved, approved-pending-disbursement, actually disbursed, outstanding, settled, refund pending / received, carry-forward pending / completed, recovery pending / completed. Status: **Missing**. The finance dashboard does not read `CashAdvance` at all.

### AP (`ap()`)
| KPI | Source | Amount basis | Status |
|---|---|---|---|
| `toPay`, `toPayValue` | reports `APPROVED_FOR_PAYMENT` | `paidAmount` if set, else reimbursable | **Defect** (risk R1): after a partial batch allocation `paidAmount` is set, so AP shows the already-paid part as still owed |
| `paidMonth` | reports paid this month | `paidAmount` | Implemented but not verified |
| `avgDays` | approval to payment, 90 days | — | Implemented but not verified |
| `failed` | `HANDOFF_FAILED` count | count | Implemented but not verified |

Missing for AP: net payable (approved expense minus advance offsets), remaining payable per report, partial-payment state, payment-reversed state, batch view, per-report payment history. Status: **Missing**. The data exists in `ap_payment_batch` / `ap_payment_allocation` (V41) but no dashboard reads it.

### Admin (`admin()`)
`spendMonth`, `submitted`, `claimants`, `cycle`, `violations`. No cash advance figures. Status: **Implemented but not verified**.

## 3. Cash advance lifecycle mapping (evidence)

| Stage | Amount field(s) | Table / field | Status |
|---|---|---|---|
| Requested | `amount` (and `requested_amount`, V40) | `cash_advance` | Implemented and verified (V40 migration tested) |
| Manager recommended | `recommended_amount`, `recommended_treatment` | `cash_advance` (V40) | Implemented but not verified (no MySQL test of the decision path) |
| Finance approved | `approved_amount` | `cash_advance` (V30) | Implemented but not verified (approval path has no MySQL test) |
| Actually disbursed | `disbursed_amount`, `disbursement_*` | `cash_advance` | Implemented but not verified |
| Pending disbursement | derived: approved minus disbursed | — | Missing as a stored or reported value |
| Outstanding | `outstanding_balance` (cache) and the calculator | `cash_advance`, `CashAdvanceBalanceCalculator` | Implemented and verified (calculator, drift, MySQL drift test) |
| Eligible expense offset | `cash_advance_adjustment.adjusted_amount` (active rows) | V39 `reversed` flag | Implemented and verified (reversal and MySQL race tests) |
| Refund received | `cash_advance_repayment.amount` | `cash_advance_repayment` | Implemented and verified (repayment race MySQL test) |
| Refund pending | — | — | Missing |
| Carry-forward in / out | `carried_in_amount`, `carried_out_amount`, `cash_advance_carry_forward` | `cash_advance` and carry-forward table | Implemented but not verified |
| Payroll recovery / pending | `salary_deduction_request`, `cash_advance_recovery_case` | recovery tables | Implemented but not verified |
| Reversed movements | `cash_advance_adjustment.reversed`, `reversal_reason`, audit log | V39 | Implemented and verified |
| Settled / reconciled | `settled_at`, `settlement_status`, reconciliation run tables | `cash_advance`, `cash_advance_reconciliation_*` | Implemented but not verified |

## 4. Expense reimbursement lifecycle mapping (evidence)

| Stage | Amount field | Status |
|---|---|---|
| Draft | line items of `DRAFT` reports | Implemented but not verified |
| Submitted claimed | `total_amount` | Implemented and verified (existing tests) |
| Manager recommended / approved | line-item approvals (`ApprovalLineItemReview`) | Implemented but not verified |
| Finance approved eligible | line-level verification (`FinanceVerificationReview`) | Implemented but not verified |
| Rejected / disallowed | line status `rejected` | Partially implemented (no stored disallowed total) |
| Pending verification | derived | Missing as a stored value |
| Advance-funded | `cash_advance_adjustment` sum for the report | Implemented and verified |
| Employee-funded | — | Missing (derived value not defined) |
| Net reimbursement payable | `reimbursable_amount` | Implemented and verified (`ReimbursementCalculator`, write-off tests) |
| AP pending | `APPROVED_FOR_PAYMENT` | Implemented but not verified |
| Partially paid / actually paid | `paid_amount`, batch allocations (V41) | Partially implemented: batch allocations verified (AP MySQL test); no dashboard uses them |
| Payment failed | `HANDOFF_FAILED` | Partially implemented: exists for invoice handoff, not for bank failure |
| Payment reversed | — | Missing |
| Remaining payable | `reimbursable_amount` minus `paid_amount` | Missing as a reported value; the code computes it in `CashAdvanceBalanceRules.reimbursementStillOwed` |

## 5. Action to amount mapping

| Action (role) | Amount changed | Other record | Status |
|---|---|---|---|
| Create advance (employee) | requested amount | advance row | Implemented and verified |
| Manager decision (manager) | recommended / approved amount; exposure exception | advance row, manager fields (V40) | Implemented but not verified on MySQL |
| Finance approval (finance) | approved amount (frozen) | `approved_amount` | Implemented but not verified |
| Disbursement (finance) | `disbursed_amount` | disbursement fields | Implemented but not verified (race test passes) |
| Acknowledge cash (employee) | none | `disbursement_acknowledged_by` | Partially implemented (name only; no acknowledgement state) |
| Offset (finance) | adjusted amount, outstanding | adjustment row | Implemented and verified |
| Reverse offset (finance) | restored outstanding | `reversed` flag, audit | Implemented and verified |
| Repayment (finance) | outstanding | repayment row | Implemented and verified |
| Carry-forward (finance) | source out, destination in | carry-forward row | Implemented but not verified |
| Recovery (finance / payroll) | outstanding | recovery / deduction rows | Implemented but not verified |
| Approve expense (manager, finance) | eligible amount | line reviews | Implemented but not verified |
| AP single payment (AP) | paid amount | report payment fields | Implemented and verified (MySQL concurrency test) |
| AP batch payment (AP) | per-report allocation | batch and allocation rows | Implemented and verified (MySQL concurrency test) |

## 6. Role to role data flow

- Employee to manager: advance request (amount, purpose). Report submission (claimed amount).
- Manager to Finance: recommendation (`recommended_amount`, treatment). Report approval (line decisions).
- Finance to AP: report released for payment (`APPROVED_FOR_PAYMENT`, reimbursable amount).
- AP to employee: payment (`PAYMENT_COMPLETED`, paid amount).
- Finance to employee: disbursement (`disbursed_amount`), repayment and recovery.

Gap: no dashboard shows a figure that crosses from one role to the next. Each role sees only its own queue.

## 7. Financial equations: status

| Relationship | Equation | Status |
|---|---|---|
| R1 request distribution | requested = approved + rejected + withdrawn + pending + other | Missing (no stored distribution; no report) |
| R2 approval distribution | approved = disbursed + pending disbursement + cancelled | Missing |
| R3 disbursed reconciliation | disbursed = outstanding + offsets + refunds + recoveries + carry-out + other | Partially implemented: `CashAdvanceReconciliationCalculator` checks a version of this, against the cache |
| R4 closing outstanding | closing = opening + disbursed + carry-in − offsets − refunds − recoveries − carry-out + reversals | Implemented and verified (calculator tests; reversal test) |
| R5 claim distribution | submitted = approved + rejected + pending + finalised | Missing |
| R6 approved funding | approved = advance-funded + employee-funded + other | Missing (employee-funded undefined) |
| R7 net payable | net = approved − advance funding − other | Implemented but not verified (`reimbursable`); the dashboard does not use it |
| R8 AP | net = confirmed net payments + remaining | Partially implemented (per-report paid and remaining in batch rules; no reporting) |
| R9 carry-forward | carry-out at source = carry-in at destination; no cash | Implemented but not verified (carry-forward tests) |
| R10 closure | zero outstanding, no pending refund, recovery, disbursement, carry-forward, or payment issue | Partially implemented (closure requires zero outstanding; other conditions not all checked) |

## 8. Missing database fields and historical gaps

- Pending disbursement amount: not stored (derived only).
- Refund pending: no state on repayments.
- Employee-funded amount and "other funding": not defined anywhere.
- Disallowed amount: not stored at report level.
- Payment reversal: no record type.
- Bank failure on a single payment: no record (only invoice handoff failure).
- Acknowledgement: no state beyond a name (Milestone 2 scope).
- Partial payment history for single payments: only the batch path keeps history (V41). Single payments keep only report-level fields.
- Historical: reports paid before V41 have no allocation rows. Their `paid_amount` is the full payment, so they are consistent; they simply have no batch history.

## 9. Proposed API response structure (for approval, not built)

```json
{
  "role": "FINANCE",
  "period": {"from": "2026-11-01", "to": "2026-11-30", "currency": "INR"},
  "summary": [
    {"key": "approvedPendingDisbursement", "label": "Approved, pending disbursement",
     "amount": "120000.00", "count": 4, "drill": "/xms/dashboard/finance/drill?key=approvedPendingDisbursement"}
  ],
  "breakdown": [{"status": "APPROVED", "amount": "120000.00", "count": 4}],
  "reconciliation": {"ok": false, "differences": [{"advanceId": "...", "stored": "900.00", "computed": "700.00", "difference": "200.00"}]},
  "warnings": ["1 advance drifts from its records"]
}
```

Drill-down response: a list of records, each with transaction ID, advance or report ID, employee, movement type, amount, status, date, source transaction, reversal reference, and document link.

## 10. Recommended UI layout (for approval)

- Employee: four cash advance tiles, four reimbursement tiles, one overall position strip, and an action list. Each tile opens its records.
- Manager: review queue first (request, exposure, recommendation), then team expense review, then team totals.
- Finance: verification queue, disbursement queue, reconciliation and exceptions, then AP handoff.
- AP: payable queue with net payable and remaining per report, batch entry, payment history.

## 11. Risks

| ID | Risk | Evidence | Severity |
|---|---|---|---|
| R1 | AP "amount to pay" overstates or misstates after a partial batch | `paymentAmounts()` in `DashboardServiceImpl` uses `paidAmount`; batch allocations set it | High (introduced by Decision 3; to fix) |
| R2 | Expense reporting "awaiting payment" uses the reimbursable amount, not the remaining payable | `ExpenseReportingServiceImpl.reimbursements()` | Medium (to fix) |
| R3 | Employee "awaiting payment" shows gross, not net | `employee()` stage amounts | Medium |
| R4 | Dashboards are unverified: no service test for any role's `*()` method | test search | High |
| R5 | No cash advance figures on Finance or AP dashboards | `finance()`, `ap()` | High (spec gap) |
| R6 | Double counting across roles if totals are added naively (e.g. manager team exposure plus employee exposure) | design | Medium (needs rule) |
| R7 | No filters or drill-down; totals cannot be traced | controller | High (spec gap) |

## 12. Implementation plan (for approval)

| Milestone | Scope | Depends on | Acceptance |
|---|---|---|---|
| A | This audit | — | Approved |
| B | Card-to-ledger mapping, shared calculation service, fix R1 and R2 | A | Every card maps to a documented source; R1 and R2 tested on MySQL |
| C | Shared financial aggregation service (no duplicate formulas) | B | Equations R4, R6, R7, R8 asserted in tests |
| D | Employee dashboard | C | Service tests per card; drill-down lists verified |
| E | Manager dashboard | C | Recommendation counts and exposure per request tested |
| F | Finance dashboard | C | Advance and expense figures reconcile to records |
| G | AP dashboard | C, R1 fixed | Net and remaining payable tested; batch history shown |
| H | Drill-down endpoints and filters | D–G | Each card returns its records; permissions tested per role |
| I | Reconciliation and exceptions | C | Drift and mismatch listed with difference |
| J | Role, integration and concurrency tests | all | Test matrix in the brief passes on MySQL |
| K | Production readiness and documentation | J | Sign-off |

Business decisions required before B: whether employee-funded and other-funding amounts are defined; whether
pending-disbursement and refund-pending values are reported as amounts or only as counts; the reporting period
rule for advances (by request date, trip date or disbursement date); and whether manager team exposure appears
in organisation-wide totals (R6).
