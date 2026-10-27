# Cash advance recovery and reconciliation policy

**Status: DRAFT. Not approved.** Drafted from the rules given by the company, for Finance to review and approve.
Values marked *editable* are stored as system settings and can be changed from Masters → Policies. Nothing here takes
effect for a real advance until Finance approves it and the owner of the policy records the approval.

## 1. Financial year

- The financial year runs **1 April to 31 March**.
- Reconciliation periods: **monthly** (default), **quarterly**, **half-yearly** and **yearly**. The default period and the
  availability of each period are *editable*.

## 2. Pending amounts

There is **no write-off**. Any pending cash advance amount is resolved through one of the three routes below.

### Route 1: Carry forward to the next expense

Carry forward is the default treatment for an unused advance balance. The rules below are the policy (CF-01 to CF-10).

| Rule | Policy |
|---|---|
| CF-01 | Each employee's outstanding advance balance is tracked across trips and months. |
| CF-02 | Any unused balance remains outstanding and carries forward by default. |
| CF-03 | The employee is not asked to repay the unused amount after each expense report. |
| CF-04 | Eligible expenses approved through the expense verification workflow reduce the outstanding advance. |
| CF-05 | When an employee requests an advance for a new trip, the available carry-forward balance is retrieved. |
| CF-06 | If the new request is the total trip budget, the existing balance is subtracted to give the additional amount Finance disburses. |
| CF-07 | The same outstanding funds may not be allocated to more than one active trip. |
| CF-08 | Authorized Finance users decide, per policy, to retain the balance, carry it forward, start salary recovery, or settle it. |
| CF-09 | The ledger is updated when the related expense adjustment, disbursement, repayment or payroll recovery is confirmed. |
| CF-10 | The audit trail records the original advance, expenses, carry-forward amount, Finance decisions and settlement transactions. |

*Editable:* whether the carry-forward mechanism is switched on in the running system. It stays **off** until Finance
approves this policy, even though the rules above describe the intended default.

Additional conditions that apply to a carry-forward allocation (from the approval design):
- The requester is not the approver. A Finance approver other than the requester approves it.
- The amount is at most the verified unallocated balance of the source advance, across all its active allocations.
- The destination advance is approved and not yet disbursed.
- An overdue advance cannot be carried forward, and carry-forward does not clear the overdue status or change any deadline.
- Allocations across review-cycle boundaries are not allowed until Finance confirms the cycle rules.

### Route 2: Refund request to the employee
1. The employee is notified by **email and in-app notification** that a refund of the pending amount is due.
2. The employee has **1 day** to return the amount. *(Calendar or working day to be confirmed by Finance.)*
3. If the amount is returned within the grace period, the advance is settled through the repayment process.
4. If it is not returned within the grace period, the case moves to Route 3 automatically.

*Editable:* grace period in days (default **1**).

### Route 3: Salary recovery report to the CEO
Used when Route 2 is not completed in time. The system sends an email to the CEO with one line per employee:
- employee ID
- employee name
- cash advance issued
- total verified expense
- total outstanding amount to recover from salary

The CEO processes the deduction in payroll. **The system does not record the deduction as completed.** The case is
marked *sent to payroll* until payroll confirmation is recorded by Finance.

*Editable:* the CEO's email address (required before Route 3 can send).

## 3. Open items for Finance (not decided here)
- Email provider and sender address. No mail service is configured yet.
- Whether the one-day grace period is calendar or working days.
- Deduction limits per pay cycle, and what happens to an open balance when an employee leaves.
- Whether Route 3 applies to amounts below a threshold, if any.
- Financial-year-based review cycles beyond the reconciliation periods above.

## 4. Approval
| Role | Name | Date | Decision |
|---|---|---|---|
| Finance approver | | | |
| HR / Payroll | | | |
