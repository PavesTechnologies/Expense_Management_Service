# Cash Advance Implementation Tracker

Working tree: `Expense_Management_Service` (backend) and `intranet-fe` (frontend). Nothing has been committed by this work. Unrelated uncommitted changes already present in both repos were left in place.

---

## Milestone 0: Baseline audit

### Environment facts

| Item | Finding | Impact |
|---|---|---|
| Database | `spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect`; datasource from `DB_URL` | The brief assumes PostgreSQL. Migrations must target the real database. Confirm before writing any migration. |
| Flyway | `spring.flyway.enabled=false`. Migrations `V1`–`V38` exist as files, but are not run at startup. Schema changes rely on `spring.jpa.hibernate.ddl-auto=update`. | Milestone 1.2 needs a real migration path. Decision needed: enable Flyway, or run migration scripts manually. |
| Migration V30 | Adds `cash_advance.approved_amount` (DECIMAL(19,4)). | `approved_amount` already had a migration; the entity field was already mapped. |
| New schema (this session) | `disbursed_amount`, `requested_amount`, `recommended_*`, `manager_justification`, `manager_decided_*`, `exposure_exception_*`, `deferred_*` on `cash_advance`; `reversed`, `reversed_at`, `reversed_by`, `reversal_reason` on `cash_advance_adjustment`. No migration file yet. | Must be added as a Flyway migration in Milestone 1. |
| Config | `cash_advance.max_employee_exposure` is read from `system_configuration`, but no row is seeded. | No exposure limit is enforced until Finance creates the row. Decision: default limit value. |
| Git | Both repos have many uncommitted changes, including pre-existing ones (e.g. `DashboardServiceImpl`, `ApprovalWorkflowServiceImpl`, `MyAdvancesPage`). | Commit in logical units only, after review. |

### Requirement status

| # | Requirement | Status | Evidence | Required action |
|---|---|---|---|---|
| 1 | Advance-wise outstanding | Implemented | `CashAdvanceServiceImpl.ledgerOutstanding`; `CashAdvanceBalanceRules.ledgerBalance` | Add integration test against real rows. |
| 2 | Employee-level aggregate outstanding | Implemented | `getEmployeeBalanceForApproval` returns `pendingBalanceTotal` (sum of open advances, each via ledger). | Verify that the employee dashboard uses the same figure. |
| 3 | Configured exposure limit | Partial | Read from `system_configuration` key `cash_advance.max_employee_exposure`. Parser `CashAdvanceExposure.parseLimit`, unit-tested (7 tests). No seeded row. | Add seed / admin screen for the limit. Decision on default. |
| 4 | Manager decision endpoint | Implemented | `POST /xms/employee/cash-advances/{id}/manager-decision`; `CashAdvanceController.managerDecision`. | **No integration or unit test.** |
| 5 | APPROVE_FULL | Implemented | `managerDecision` case `APPROVE_FULL`; exposure justification required above limit. | Test. |
| 6 | APPROVE_REDUCED | Implemented | Case `APPROVE_REDUCED`; checks: less than requested, round block, justification. | Test. |
| 7 | SET_OFF | Partial | Recommendation recorded; source ledger check. Finance must still propose carry-forward through `CashAdvanceCarryForwardService`. | Test end to end with carry-forward. |
| 8 | DEFER | Implemented | Case `DEFER`; status `DEFERRED`; submit accepts `DEFERRED`. | Test. Frontend status label not added. |
| 9 | REJECT | Implemented | Case `REJECT` delegates to `reject`. | Test. |
| 10 | Finance final authorization | Implemented | `approve()` stamps `exposure_exception_authorized_by/at` on final approval when an exception was recorded. | Test. |
| 11 | Adjustment reversal instead of deletion | Partial | `CashAdvanceAdjustmentServiceImpl.reverse()`; `delete()` delegates to it; `POST /{id}/reverse`. Readers filtered: `ApprovalWorkflowServiceImpl`, `ReimbursementCalculator`, `CashAdvanceReconciliationCalculator`, `CashAdvanceRecoveryService`, `CashAdvanceServiceImpl` (offsets), `CashAdvanceUtilizationCalculator`. | **No reversal test.** Existing `delete_*` tests (lines 257, 268) assert old behaviour only indirectly. Dashboard, Finance verification and carry-forward readers not yet audited. |
| 12 | Advance row locking | Partial | `findByIdForUpdate` used in: adjustment create and reverse, approve, disburse, manager decision, repay (line 1119), carry-forward, salary deduction, recovery. | Not yet checked: Finance verification and AP payment paths. |
| 13 | Disbursement locking | Implemented | `disburse()` locks the advance before the status check. | Concurrency test not written. |
| 14 | Set-off guard before disbursement | Implemented | `disburse()` throws when `carriedIn < recommendedSetOffAmount` for `SET_OFF`. | Test. |
| 15 | Frontend manager decision popup | Implemented | `CashAdvanceApprovalsList.jsx` decision options, exposure panel, client checks; `cashAdvanceApi.managerDecision`. | Browser verification not done. Chrome not used. |

### Existing test status (run before any change in this milestone)

Run on the current working tree, after the last backend change, which was made before the run.

| Suite | Result |
|---|---|
| `CashAdvanceExposureTest` | 7 passed |
| `CashAdvanceServiceImplTest` | 33 passed |
| `CashAdvanceAdjustmentServiceImplTest` | 10 passed |
| `CashAdvanceControllerTest` | 15 passed |
| `CashAdvanceAdjustmentControllerTest` | 7 passed |
| `CashAdvanceRecoveryServiceTest` | 21 passed |
| `ReimbursementCalculatorTest` | 4 passed |
| `ReimbursementWriteOffTest` | 3 passed |
| `CashAdvanceReconciliationServiceTest` | 11 passed |
| `CashAdvanceCarryForwardServiceTest` | 34 passed |
| Frontend `approval-engine` | 13 passed |
| Frontend `pages/cash-advance` | 53 passed, 2 skipped (run earlier, before the approval-popup change) |

Not yet run: the full backend suite, the full frontend suite after the last change, any browser or E2E test.

### Gaps carried into Milestone 1

1. Reapplying an adjustment after reversal is blocked by the unique key on `(advance_id, report_id)`.
2. Reversal and manager-decision paths have no tests.
3. No Flyway migration exists for the new columns.
4. The exposure limit has no seeded value.
5. The Finance verification and AP payment paths are not audited for locking or reversed-offset handling.
6. Idempotency for disbursement, AP payment and salary confirmation is not reviewed.

### Decisions needed before Milestone 1

1. **Database and migrations.** The application is MySQL with Flyway disabled. Should migrations be written for MySQL and run by Flyway, with Flyway enabled? The brief says PostgreSQL. Confirm the target.
2. **Exposure default.** Should a default limit be seeded, or should no limit apply until Finance sets one?
3. **Reapplication after reversal.** Keep the unique key and move the uniqueness to active rows only, or allow multiple rows per report with a partial unique index? Both need a migration.


---

## Decisions (confirmed by owner)

| # | Topic | Decision |
|---|---|---|
| D1 | Database | Keep the existing production engine. Do not migrate or change engine. Migrations must be MySQL 8 compatible. Do not use `ddl-auto=update` as the production migration strategy. |
| D2 | Exposure limit | `cash_advance.max_employee_exposure` stays configurable by Finance. No default is seeded. Without a configured limit: calculate and display exposure, do not block. With a limit: enforce it. |
| D3 | Reapplication after reversal | Allowed, as a new adjustment row for the same advance and report. The reversed row is kept permanently. Duplicate active adjustments are prevented by the database. Idempotency is retained. All balances and report eligibility are revalidated. |

## Confirmed database findings

Source files inspected: `application.properties`, `pom.xml`, `docker-compose*.yml`, `Dockerfile`, migration SQL, service SQL. The `.env` file was not read because it contains credentials.

| Evidence | Finding |
|---|---|
| `spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver` | MySQL driver |
| `spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect` | MySQL dialect |
| `pom.xml`: `mysql-connector-j`, `flyway-mysql` | MySQL is the only production driver. H2 is test-only, for the e2e tests. |
| `docker-compose.cdc.yml`: "Debezium MySQL connector on AWS RDS" | Production is MySQL on AWS RDS |
| `V33__...sql`: triggers on `cash_advance` | MySQL trigger syntax |
| `V30__...sql`: `ADD CONSTRAINT uk_advance_report UNIQUE (advance_id, report_id)` | Constraint was added by migration on MySQL |
| `spring.flyway.enabled=false` | Migrations are not run at startup. The applied state of `V1`–`V38` in each environment is unknown. |

**Conclusion: the target is MySQL (AWS RDS). PostgreSQL is not used.**

## Migration strategy

- New migrations are versioned `V39` and later, written in MySQL 8 syntax.
- Each column or index change is guarded with `information_schema` checks, so a migration that runs against a database where `ddl-auto` already created the object does not fail.
- Flyway stays disabled in this milestone. Enabling it needs a baseline decision for each environment (`flyway_schema_history` does not exist yet). This is a separate approval item.
- The unique key `uk_advance_report` is dropped only if it exists. It is replaced by a unique index on a generated column `active_offset_key`, which is set only for non-reversed rows. MySQL allows multiple NULLs in a unique index, so reversed rows do not conflict.


## Milestone 1: Financial integrity

Status: **code complete and unit-tested. Not complete against the acceptance criteria** (see "Not done"). Awaiting owner approval before Milestone 2.

### Changes

| Area | Change | Files |
|---|---|---|
| Reversal | `reverse(id, reason)` marks the row reversed, records who, when and why, recomputes the balance from active rows, reverses the budget movement, refreshes reimbursable, and writes audit entries. The row is never deleted. `delete()` now delegates to reversal. | `CashAdvanceAdjustmentServiceImpl`, `CashAdvanceAdjustmentService`, `CashAdvanceAdjustmentController` (`POST /{id}/reverse`) |
| Reversed-row exclusion | `isActive` filter at every reader of adjustments: advance offsets (create, reverse, settlement), utilization, reconciliation, recovery, reimbursable (`ReimbursementCalculator`), approval-time capping and status changes (`ApprovalWorkflowServiceImpl`). | 7 files under `service/impl` |
| Reapplication | The old `uk_advance_report` constraint is removed from the entity. A new offset after a reversal creates a new row. Duplicate active offsets are rejected in code and by the database. | `CashAdvanceAdjustment` (entity), `CashAdvanceAdjustmentServiceImpl` |
| Locking | Advance row locked (`findByIdForUpdate`) before balances are read, in offset create and reverse. Already locked: approve, disburse, manager decision, repayment, carry-forward, salary deduction, recovery. | as above |
| Database | `V39`: reversal columns, generated `active_offset_key` (NULL when reversed), unique index on it, guarded drop of `uk_advance_report`. `V40`: the cash advance columns from earlier decisions (requested amount, recommendation, exposure exception, deferral, disbursed amount), guarded. | `db/migration/V39__cash_advance_adjustment_reversal.sql`, `V40__cash_advance_manager_decision_and_exposure.sql` |

### Database migrations

- **Engine:** MySQL 8. Written in MySQL syntax.
- **Not run.** Flyway is disabled and there is no MySQL instance here, so neither migration has been executed. Before deploying: run the pre-flight query in `V39` on a copy of production, then apply the two files, either by hand or by enabling Flyway with a baseline.
- **Rollback:** `V39` is reversed by dropping `uk_cash_advance_adjustment_active_offset` and the four reversal columns. `V40` is reversed by dropping its added columns. Neither file deletes data.
- `V39` drops `uk_advance_report`. Until the active-offset index exists, nothing in the database prevents two rows for the same advance and report. Both steps must happen in the same deployment.

### Tests executed (backend)

Command: `./mvnw test -Dtest=...` on the 15 classes below. Exit code 0.

| Class | Tests | Result |
|---|---|---|
| `CashAdvanceAdjustmentReversalTest` (new) | 13 | 13 passed |
| `CashAdvanceAdjustmentServiceImplTest` | 10 | 10 passed |
| `CashAdvanceAdjustmentControllerTest` | 7 | 7 passed |
| `CashAdvanceServiceImplTest` | 33 | 33 passed |
| `CashAdvanceControllerTest` | 15 | 15 passed |
| `CashAdvanceCarryForwardServiceTest` | 34 | 34 passed |
| `CashAdvanceRecoveryServiceTest` | 21 | 21 passed |
| `CashAdvanceReconciliationServiceTest` | 11 | 11 passed |
| `CashAdvanceReconciliationCalculatorTest` | 13 | 13 passed |
| `CashAdvanceBalanceLedgerTest` | 15 | 15 passed |
| `CashAdvanceBalanceRulesTest` | 6 | 6 passed |
| `CashAdvanceBudgetMovementsTest` | 13 | 13 passed |
| `CashAdvanceExposureTest` | 7 | 7 passed |
| `ReimbursementCalculatorTest` | 4 | 4 passed |
| `ReimbursementWriteOffTest` | 3 | 3 passed |
| **Total** | **205** | **205 passed, 0 failed** |

`CashAdvanceAdjustmentReversalTest` covers: reverse keeps the row and restores the balance; the balance is recomputed from active rows, not added back; the advance lock is taken before reads; reversing twice is rejected; a blank or missing reason is rejected; reversing an offset on a paid report is rejected; an employee cannot reverse; reapplying after reversal creates a new active row and keeps the old one; a duplicate active offset is rejected; the lock is taken before reads on create; the create balance counts active rows only; an idempotency key matching a reversed offset returns that record and applies nothing; reversed rows do not reduce the reimbursable amount.

Not run: the full backend suite; any frontend test (no frontend change in this milestone); any browser or E2E test.

### Balance readers inspected

| Reader | Source of balance | Reversed rows excluded | Notes |
|---|---|---|---|
| `CashAdvanceServiceImpl` (create, reverse, settlement, approval balance) | Recomputed from active offsets and repayments | Yes | Approval balance and exposure use the ledger, not the stored field. |
| `CashAdvanceUtilizationCalculator` | Active offsets | Yes | Also compares with the stored field. |
| `CashAdvanceReconciliationCalculator` | Active offsets, compared with the stored field | Yes | |
| `CashAdvanceRecoveryService` | Active offsets | Yes | |
| `ReimbursementCalculator` (used by ApPayment, ExpenseLineItem, adjustment refresh) | Active offsets | Yes | Filter is inside the calculator, so every caller is covered. |
| `ApprovalWorkflowServiceImpl` (submission, review, approval capping) | Active offsets | Yes | |
| `CashAdvanceCarryForwardService` (`outstanding`) | **Stored** `outstandingBalance` | Indirectly | Reverse writes the stored value from active rows, so it stays correct. |
| `CashAdvancePayrollPosting`, `CashAdvanceRecoveryService.outstanding` | **Stored** `outstandingBalance` | Indirectly | As above. |
| `DashboardServiceImpl` (employee open balance), `ExpenseReportingServiceImpl`, `NotificationEventListener` | **Stored** `outstandingBalance` (SQL sum) | Indirectly | As above. |

Finance verification and AP payment were checked for writes to advances or offsets: none found.

### Not done (acceptance criteria not met)

1. **Migrations not executed** on any MySQL database. The SQL is unverified.
2. **Concurrency is not tested.** Lock order is asserted in unit tests. Two real concurrent requests have not been run against MySQL, and unit tests cannot show that the database index blocks a duplicate.
3. **Stored balance is still read directly** by carry-forward, payroll posting, recovery, dashboard and notifications. These are correct only because every writer keeps the stored value equal to the ledger. A drift check, or switching these readers to recomputed values, is still needed.
4. **Idempotency not reviewed** for AP payment and salary deduction confirmation. Disbursement idempotency relies on the state lock and status check, which is untested. Repayment idempotency exists in the entity but was not re-verified here.
5. **Dashboard and Finance screens** not checked in a browser.
6. **Reversal of an offset whose report is pending AP payment** is allowed by the code, but the effect on the AP payment amount has not been tested.

### Decisions applied

- D1: MySQL, Flyway-compatible migrations, no `ddl-auto` in production.
- D2: Exposure: no default limit seeded. Calculated and shown without a limit; enforced once Finance configures one.
- D3: Reapplication after reversal: new row, reversed row kept, active uniqueness enforced by the database.

## Milestone 1: closure attempt (second round)

Status: **not declared complete.** Items 1 to 3 and 5 are done with evidence below. Item 4 (payment idempotency) is partly done. Items with open limits are listed at the end. Milestone 2 has not been started.

### Test database

- Engine: **MySQL 8.0.46**, Docker image `mysql:8.0`, container `ca-mysql-test`, bound to 127.0.0.1:33306. It is a local, disposable container. It is not the RDS dev instance, and nothing was run against production or the shared dev database.
- Databases: `ca_baseline` (schema generated by Hibernate from the committed HEAD entity model, 60 tables), `ca_upg2`, `ca_upg3`, `ca_dup`, `ca_v38` (scratch copies), and `ca_it` (baseline + V39 + V40, used by the concurrency tests).
- Baseline is an approximation: the real environments' schema is unknown, because Flyway is disabled and each environment was built by `ddl-auto=update`.

### 1. MySQL migration validation

| Check | Result |
|---|---|
| V39 on baseline (copy with seed data, `ca_upg2`) | Exit 0 |
| V40 on baseline | Exit 0 |
| Re-running V39 and V40 after success (idempotency) | Both exit 0 |
| Generated column `active_offset_key`: NULL for reversed rows, `advance:report` for active rows | Verified (2 reversed rows NULL, 3 active rows set) |
| Unique index on the active key: second active row for the same advance and report | Refused, error 1062 |
| Reversed row kept, then a new active row for the same pair (reapplication) | Succeeded; 5 rows total, nothing deleted |
| Second active row for the reapplied pair | Refused, error 1062 |
| Existing duplicate active data (`ca_dup`): pre-flight query | Finds the duplicate group |
| Existing duplicate active data: V39 | Fails at the unique index; the columns added before that step remain (not atomic). After removing the duplicate, the rerun completed with exit 0. |
| V39 against the baseline before the fix | **Failed**, error 1553: the foreign key on `advance_id` needs `uk_advance_report`. Fixed by creating `idx_cash_advance_adjustment_advance` first. |
| Ordering: V1 to V38 applied in version order on top of the baseline (`ca_v38`) | **13 of 38 fail**: V1, V2, V3, V4, V5, V6, V7, V8, V12, V18, V19, V21, V30. Errors are duplicate columns (1060) or dropping columns that do not exist (1091). The entity-generated schema already contains what those files add. |

Consequence: V1 to V38 cannot be replayed on a schema generated from the entities. Each existing environment has its own history, so Flyway must start from a recorded baseline for each environment, after which only V39 and later run. This is a decision for you, not something I changed.

Flyway/CLAUDE.md findings:
- `V6` is duplicated (two files). `V34` is duplicated too: `V34__cash_advance_budget_fiscal_year.sql` and `V34__tax_line_supplier_gstin.sql`. Flyway refuses duplicate versions, so Flyway cannot be enabled until one of each pair is renamed. I did not rename them, because a rename changes history for any environment that already recorded them.
- `application.properties` still has `spring.flyway.enabled=false`. Unchanged.

### 2. Real-database concurrency

Harness: real service and repositories, real MySQL 8 locks. Each thread runs its own transaction. Threads start together on a latch. Fixtures are inserted over JDBC and removed afterwards. Both classes run only when `CA_IT_MYSQL_URL` points to a database whose name contains `ca_it`.

| Class | Tests | Result |
|---|---|---|
| `CashAdvanceConcurrencyMysqlTest` | 5 | **5 passed** |
| `CashAdvanceDisbursementRepaymentMysqlTest` | 3 | **3 passed** |

Scenarios and checks:
- Two adjustments of 600 on a 1000 balance: exactly one succeeds, the other is refused, stored and ledger balances are 400, never negative.
- Two reversals of the same offset: exactly one succeeds, the balance is restored once, the row is kept as reversed, the budget reversal runs once.
- Create racing with reversal: the stored balance equals the amount minus the active offsets; only business rejections are raised.
- Two creates for the same advance and report: exactly one active row.
- A rejected offset leaves no row and no change to the balance.
- Two disbursements of one approved advance: exactly one succeeds; one audit record of `CASH_ADVANCE_DISBURSED`.
- Two repayments that together exceed the balance: the second is refused; balance 400; one repayment row.
- Two repayments with the same reference: recorded once; balance 900.

**Defect found and fixed by this test:** `reverse()` loaded the adjustment before taking the advance lock. The second concurrent reversal saw a stale `reversed = false`, and then failed on the version check with a generic error instead of a clean refusal. Fix: the advance id is read through a scalar query, the advance is locked, and only then is the adjustment loaded. Create and reverse now run at READ COMMITTED, so a consistent read sees committed data (MySQL's default REPEATABLE READ would otherwise keep a stale snapshot).

Not run against MySQL: the approval and manager-decision paths, and AP completion under real concurrency (see item 4).

### 3. Outstanding balance consistency

**Audit of every reader and writer of `outstandingBalance`:**

| Location | Role | Finding |
|---|---|---|
| `CashAdvanceAdjustmentServiceImpl` create and reverse | Writer | Recomputed from active offsets and repayments. Consistent. |
| `ApprovalWorkflowServiceImpl` (cap and recalculation) | Writer | Uses `ledgerBalance`. Consistent. |
| `CashAdvanceBalanceLedger.applyBalance` (carry-forward, status recheck) | Writer | Caller supplies the value, computed from the formula. Consistent. |
| `CashAdvanceCarryForwardService` (apply, reverse) | Writer | Via ledger. Consistent. |
| `CashAdvancePayrollPosting.post` | Writer | Subtracts from the cached value and writes a repayment record. Consistent only while the cache equals the records. |
| `CashAdvanceServiceImpl.repay` | Writer | Subtracts from the cached value and writes a repayment record. Consistent only while the cache equals the records. |
| Carry-forward (`outstanding`), payroll (`currentBalance`), recovery, dashboard SQL sum, expense-reporting SQL, notifications | Readers | Read the cache. Consistent only while the cache is correct. |
| Reconciliation and utilization | Readers | Already compare the cache with the records. |

**Conclusion:** the cache can drift only if a writer subtracts from the cached value instead of recomputing, and the cache was already wrong before that write. No such case was found in the current code, but nothing prevents it.

**Decision applied:** one authoritative formula, with the cache kept transactionally in step and a drift check added.
- `CashAdvanceBalanceCalculator` is the single place that defines the formula and the summing rules: `outstanding = amount − carriedOut − active offsets − repayments`. The static helpers `activeOffsets` and `repaymentTotal` are the only summing code. Create, reverse and the cash-advance service now use them, so the inline copies are gone.
- `CashAdvanceBalanceDriftService` and `GET /xms/finance/cash-advance-balances/drift` (Finance and Admin, read-only) list every advance whose stored value differs from the calculation. Repair is not automated.
- Readers were not rewired from the cache to the calculator. Carry-forward, payroll, recovery and dashboard tests depend on the cached field, and rewiring them would rewrite those tests. This is a decision for you (see Open items).

**Tests (reversed rows excluded):**
- `CashAdvanceBalanceCalculatorTest` (new, 5 tests): active offsets only; a reversed offset alone leaves the full amount; a negative result is refused, not clamped; null lists are zero; drift is reported only for mismatched or null cached values.
- `CashAdvanceAdjustmentReversalTest`: the balance after reversal is recomputed from active rows; reimbursable ignores reversed rows.
- Drift endpoint: no controller test written yet (Open items).

### 4. Payment idempotency

| Path | Protection found | Change made | Test |
|---|---|---|---|
| AP payment confirmation (`ApPaymentServiceImpl.markPaymentCompleted`) | Status check only, on an unlocked read. A concurrent second confirmation could consume budget and write payment fields. The `@Version` check would stop the second commit, but as a generic failure. | Report read under `PESSIMISTIC_WRITE` (`ExpenseReportRepository.findByIdForUpdate`). The second confirmation sees `PAYMENT_COMPLETED` and is refused cleanly. | `ApPaymentDuplicateCompletionTest`: 2 passed. Existing `ApPaymentServiceImplTest` 35 passed. |
| Salary deduction confirmation (`SalaryDeductionService`) | Request row locked (`findByIdForUpdate`) and status checked under the lock (must be `SENT_TO_PAYROLL`). | None needed. | Existing `SalaryDeductionServiceTest` 41 passed. No new concurrency test. |
| Cash advance disbursement | Advance locked, then status checked. | None needed. | `twoDisbursementsOfOneApprovedAdvance_exactlyOnePaysOut` on MySQL. |
| Repayment | Advance locked, duplicate reference checked after the lock. | None needed. | Two MySQL tests, one for the balance race and one for the duplicate reference. |

Not done: a MySQL-backed concurrency test for AP confirmation. The wiring is large (the service has many dependencies). It is covered by the lock and a unit test only.

### 5. Reversal while AP payment is pending

Behaviour defined and implemented:
- **Payment completed:** reversal refused (`BusinessRuleViolationException`). No compensating workflow exists, so this is the safe default. Checked by `reversal_onAPaidReport_isRefused_andTheReportIsNotRecalculated`.
- **Payment pending (`APPROVED_FOR_PAYMENT`):** the reversal increases what is owed. The report stays payable. If the report owed nothing before (routing `NONE`, no payment due), it returns to `APPROVED_FOR_PAYMENT`. AP must confirm the new amount, because confirmation refuses a mismatch of more than 0.01.
- **Audit:** a `REIMBURSEMENT_RECALCULATED` entry on the report records the owed amount and routing before and after, with the reason (`Offset reversed: …`). It is written for both creation and reversal.
- **Duplicate AP payments:** prevented by the row lock described in item 4.

Tests: `reversal_onAReportWaitingForAp_increasesWhatIsOwed…`, `reversal_onAReportThatOwedNothing_returnsItToApForPayment`, `reversal_onAPaidReport_isRefused…`. All pass.

### Files changed in this round

Backend (`Expense_Management_Service`):
- `db/migration/V39__cash_advance_adjustment_reversal.sql` (generated; guarded; `idx_cash_advance_adjustment_advance` added before the drop)
- `db/migration/V40__cash_advance_manager_decision_and_exposure.sql` (generated; guarded)
- `entity/CashAdvanceAdjustment.java` (`active_offset_key` mapped read-only; old unique constraint removed from the mapping)
- `repository/CashAdvanceAdjustmentRepository.java` (`findAdvanceIdOf`)
- `repository/ExpenseReportRepository.java` (`findByIdForUpdate`)
- `service/impl/CashAdvanceAdjustmentServiceImpl.java` (reverse lock order, READ COMMITTED on create and reverse, shared summing helpers, reimbursement audit)
- `service/impl/CashAdvanceServiceImpl.java` (shared helpers; `findEntity` fix in set-off)
- `service/impl/ApPaymentServiceImpl.java` (locked report read in `markPaymentCompleted`)
- `service/impl/CashAdvanceBalanceCalculator.java` (new)
- `service/impl/CashAdvanceBalanceDriftService.java` (new)
- `controller/CashAdvanceBalanceDriftController.java` (new)

Backend tests:
- `service/impl/CashAdvanceAdjustmentReversalTest.java` (16 tests now, 3 added for pending AP)
- `service/impl/CashAdvanceAdjustmentServiceImplTest.java` (one lenient stub added)
- `service/impl/CashAdvanceBalanceCalculatorTest.java` (new, 5 tests)
- `service/impl/ApPaymentDuplicateCompletionTest.java` (new, 2 tests)
- `concurrency/CashAdvanceConcurrencyMysqlTest.java` (new, 5 tests, MySQL)
- `concurrency/CashAdvanceDisbursementRepaymentMysqlTest.java` (new, 3 tests, MySQL)

### Tests executed (backend, final run)

Command: `./mvnw test -Dtest=…` on 19 classes, exit code 0. Reports read from `target/surefire-reports`.

| Class | Tests | Result |
|---|---|---|
| `CashAdvanceConcurrencyMysqlTest` (MySQL 8.0.46, `ca_it`) | 5 | 5 passed |
| `CashAdvanceDisbursementRepaymentMysqlTest` (MySQL 8.0.46, `ca_it`) | 3 | 3 passed |
| `CashAdvanceAdjustmentReversalTest` | 16 | 16 passed |
| `CashAdvanceAdjustmentServiceImplTest` | 10 | 10 passed |
| `CashAdvanceBalanceCalculatorTest` | 5 | 5 passed |
| `CashAdvanceServiceImplTest` | 33 | 33 passed |
| `CashAdvanceControllerTest` | 15 | 15 passed |
| `CashAdvanceAdjustmentControllerTest` | 7 | 7 passed |
| `CashAdvanceRecoveryServiceTest` | 21 | 21 passed |
| `CashAdvanceReconciliationServiceTest` | 11 | 11 passed |
| `CashAdvanceReconciliationCalculatorTest` | 13 | 13 passed |
| `CashAdvanceCarryForwardServiceTest` | 34 | 34 passed |
| `CashAdvanceBalanceLedgerTest` | 15 | 15 passed |
| `CashAdvanceBalanceRulesTest` | 6 | 6 passed |
| `CashAdvanceBudgetMovementsTest` | 13 | 13 passed |
| `CashAdvanceExposureTest` | 7 | 7 passed |
| `ReimbursementCalculatorTest` | 4 | 4 passed |
| `ReimbursementWriteOffTest` | 3 | 3 passed |
| `SalaryDeductionServiceTest` | 41 | 41 passed |
| `ApPaymentServiceImplTest` | 35 | 35 passed |
| `ApPaymentDuplicateCompletionTest` | 2 | 2 passed |
| `ApPaymentControllerTest` | 15 | 15 passed |

The MySQL tests are skipped unless `CA_IT_MYSQL_URL` is set. In a normal `./mvnw test` they are skipped, not failed.

Not run: the full backend suite, the frontend suite (no frontend change this round), and any browser or E2E test.

### Open items (Milestone 1 is not complete until these are decided or closed)

1. **Flyway baseline.** Each environment needs a recorded baseline before V39 and V40 are applied, because V1 to V38 cannot be replayed (13 failures). Flyway also needs the duplicate `V6` and `V34` renamed first. Decision needed.
2. **Production run.** V39 and V40 were tested only on local copies. They have not been run against any shared or production database. Run the pre-flight query in V39 first.
3. **Readers still use the cache.** Carry-forward, payroll, recovery, dashboard and notifications read the cached field, kept in step by writers. Decision needed: rewire them to the calculator (with test updates), or keep the cache and rely on the drift endpoint.
4. **Drift endpoint** has no controller test. No repair path, by design.
5. **Concurrency not yet covered on MySQL:** approve, manager decision, AP confirmation, salary deduction confirmation.
6. **Payment reference reuse across reports.** Not blocked. Policy decision needed on whether one bank reference may cover several payments.
7. **Migration not atomic.** V39 leaves partial objects if it fails midway. Recovery is to fix the cause and rerun it, which works (verified).
8. **Reversal of a report-level offset when the report is in a bulk AP run** has not been tested.
