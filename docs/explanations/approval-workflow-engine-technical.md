# Approval Workflow Engine — Technical Documentation

**Scope:** documents the system as actually implemented, in `com.expense_management_service` (backend) and `src/pages/expense-management/approval-engine` (frontend, `intranet-fe`). It supersedes the pre-implementation design note at `docs/explanations/approval-workflow-engine-original-design-spec.md`, whose "Approver Mapping (Individual > Group > Default)" model was **not** what got built — the real system uses priority-ordered flows matched by a criteria expression, resolving approvers via four source types. Where the two disagree, this document and the code are authoritative.

**Audience:** engineers maintaining or extending this system. A companion document, `approval-workflow-engine-user-guide.md`, covers the same system from the perspective of the people who use it.

---

## 1. What this system does, and where it sits

The Approval Workflow Engine answers one question automatically for every submitted expense report: **who needs to sign off, in what order, and has that happened yet.** It sits between two other subsystems it never reaches into directly:

```
  Policy & Compliance                Approval Workflow Engine              Reimbursement Tracking
  (attaches violations to                (this system)                    (starts only once every
   line items before                                                        required level is
   submission)               ──submit──►  resolves chain                    APPROVED)
                                          ──tracks progress──►
                                          ──hands off on full approval──►
```

It never makes an approval decision itself — every `APPROVED`/`NEEDS_CORRECTION`/`REJECTED` outcome is a human action. Its job is purely to compute *who* gets to make each decision, in what order, and to advance state correctly once they do.

It replaced an older, EP06-era engine that routed by a cost-center + amount-range matrix (`ApprovalMatrix`/`ApprovalTask` tables). That engine and its tables are gone (dropped in `V11__approval_flow_engine_cleanup.sql`, itself a renumbering of what was originally `V6` — see §14.4 for why). Two of its ideas were deliberately kept: delegation (`ApprovalDelegation` carried over largely unchanged) and "materialize the whole chain up front, activate progressively."

---

## 2. Domain model

Two families of entities exist side by side: **configuration** (what an Admin sets up, edited freely) and **runtime snapshots** (frozen copies made at submission time, immune to later configuration edits). This split is the single most important structural fact about the system — see §4 for why it exists.

### 2.1 Configuration entities

| Entity | Table | Purpose |
|---|---|---|
| `ApprovalFlow` | `approval_flow` | A priority-ordered routing rule: criteria (when it applies) + an ordered list of levels. |
| `ApprovalFlowCriterion` | *(no separate table name given; FK to `approval_flow`)* | One atomic condition (`field`, `operator`, `value`), referenced by index from the flow's `criteriaPattern`. |
| `ApprovalLevel` | `approval_level` | One ordered stage in a flow's configuration (soft-capped at 10 per flow). |
| `ApprovalLevelApprover` | *(FK to `approval_level`)* | One approver-source entry within a level (a level can have several, combined per its `quorum`). |
| `DepartmentApprover` | `department_approver` | Admin-curated department → approver mapping, backing `ApproverSourceType.DEPARTMENT_OWNER`. Unique constraint on `departmentUuid` — at most one approver per department. |
| `ApprovalDelegation` | *(delegation table)* | A stand-in approver for a date window, self-set or Admin-set. |

**`ApprovalFlow` fields** (`entity/ApprovalFlow.java`): `flowId`, `name`, `priority` (ascending = evaluated first; ignored for the catch-all), `criteriaPattern` (a `@Lob` string, e.g. `"(1 AND 2) OR 3"`; null/blank for the catch-all), `isCatchAll` (exactly one row has this true), `status` (plain string, e.g. `"ACTIVE"`), plus `criteria[]` and `levels[]` (both `CascadeType.ALL`, `orphanRemoval = true` — deleting a flow deletes its criteria/levels/approvers with it).

**`ApprovalLevel` fields**: `levelId`, `levelOrder`, `levelName` (optional — see the "Level 1" fallback in §8), `quorum` (`SEQUENTIAL` / `ANY_OF` / `ALL_OF`), `approvers[]`.

**`ApprovalLevelApprover` fields**: `entryId`, `entryOrder` (meaningful only under `SEQUENTIAL`), `sourceType` (`NAMED_USER` / `REPORTING_MANAGER` / `DEPARTMENT_OWNER` / `COST_CENTER_OWNER`), `sourceReference` (only meaningful for `NAMED_USER` — an EOS employeeId).

**`ApprovalDelegation` fields** (`entity/ApprovalDelegation.java`): `delegationId`, `delegatorId`, `delegateId`, `startDate`, `endDate`, `status` (`DelegationStatus`: `SCHEDULED`/`ACTIVE`/`EXPIRED`/`CANCELLED` — but only `CANCELLED` is ever written by application code; ACTIVE-vs-SCHEDULED-vs-EXPIRED is date-driven at read time, never transitioned on a timer), `createdAt`, `updatedAt`. No `reason`/`note` field exists.

### 2.2 Runtime snapshot entities

| Entity | Snapshots | Table |
|---|---|---|
| `ApprovalLevelInstance` | One `ApprovalLevel`, for one report + submission cycle | `approval_level_instance` |
| `ApprovalAssignment` | One `ApprovalLevelApprover` entry, resolved to an actual employeeId | `approval_assignment` |
| `ApprovalLineItemReview` | The per-line-item decision within one level instance | `approval_line_item_review` |

**`ApprovalLevelInstance`**: `instanceId`, `report` (FK), `flowId` (a **plain UUID, not a JPA relation** — deliberately, so a flow can later be deleted independently of report history; also lets a resubmission ask "does the same flow still match" by UUID comparison, avoiding a false-positive from two different flows coincidentally having identical level structure), `levelOrder`/`levelName`/`quorum` (all copied at resolution time, never re-read from config afterward), `submissionCycle` (increments on resubmit/restart — distinguishes a stale cycle's rows from the current one), `status` (`LevelInstanceStatus`: `QUEUED`/`ACTIVE`/`COMPLETED`/`CANCELLED`), plus `assignments[]` and `lineItemReviews[]`.

**`ApprovalAssignment`**: `assignmentId`, `levelInstance` (FK), `approverId` (the resolved EOS employeeId — **this**, not `sourceType`, governs who may act), `sourceType` (snapshot copy, audit/display only), `entryOrder`, `status` (`AssignmentStatus`: `PENDING`/`ACTIVE`/`COMPLETED`/`SKIPPED`/`SUPERSEDED`), `supersededApproverId` (see §5.2 — in practice this is set by the self-approval substitution pass, even though its own doc comment describes an "account-removal re-resolution (§5.5)" scenario that isn't currently implemented — `AssignmentStatus.SUPERSEDED` itself is never actually set anywhere in the codebase today), `assignedAt`/`dueDate` (the SLA window — see §9).

**`ApprovalLineItemReview`**: `reviewId`, `lineItem` (FK to `ExpenseLineItem` — this engine never mutates the line item itself, it's a pure join), `levelInstance` (FK), `status` (`LineItemReviewStatus`: `PENDING`/`APPROVED`/`NEEDS_CORRECTION`), `comment` (required when `NEEDS_CORRECTION`, never required for `APPROVED`), `actedBy` (differs from the assignment's `approverId` only when a delegate acted — see §10), `actionedAt`, and a `@Version` column for optimistic locking (protects against two near-simultaneous actions racing on the same line item).

### 2.3 Entity relationship sketch

```
ApprovalFlow (config) ─┬─< ApprovalFlowCriterion
                        └─< ApprovalLevel ─< ApprovalLevelApprover

ExpenseReport ─< ApprovalLevelInstance ─┬─< ApprovalAssignment
   (submissionCycle groups a batch)     └─< ApprovalLineItemReview >─ ExpenseLineItem

ApprovalDelegation  (independent — delegatorId/delegateId are plain employeeId strings, no FK)
DepartmentApprover  (independent — departmentUuid is a plain UUID column, no FK to a Department table;
                      Department itself is remote master data via DepartmentClient)
```

---

## 3. Terminology

| Term | Meaning |
|---|---|
| **Flow** | A named, priority-ordered routing rule with criteria + levels. |
| **Catch-all flow** | The one flow with no name/priority/criteria of its own that always matches and always evaluates last — guarantees every report resolves to *some* chain. |
| **Level** | One stage in a flow (e.g. "Manager Review"). Has a quorum and one or more approver-source entries. |
| **Approver source** | How a level's approver is determined at resolution time — a specific person, the submitter's manager, their department's mapped approver, or their cost center's owner. |
| **Resolved chain** | The specific sequence of `ApprovalLevelInstance` rows materialized for one report at one submission cycle. Frozen the moment it's created. |
| **Quorum** | How many of a level's approver entries must act, and how: `SEQUENTIAL` (one after another, in `entryOrder`), `ANY_OF` (first to finish completes the level), `ALL_OF` (every entry must act). |
| **Submission cycle** | An integer that increments every time a report is resubmitted after a full restart (not every resume-in-place — see §7.2). Distinguishes the current chain's rows from a stale prior one. |
| **Delegate** | A stand-in approver for a date window, covering unavailability — self-set or Admin-set. |
| **Duplicate-approver skip** | If the same person resolves as approver at two points in one chain, the later occurrence is auto-skipped. |
| **Self-approval cascade** | If a resolved approver turns out to be the report's own submitter, that assignment is silently substituted (delegate → manager → Default Approver) before anyone is ever notified. |
| **Needs Correction** | A non-terminal per-line-item outcome: the approver flags a specific line with a required comment; the report returns to the employee for just that fix, without restarting the whole chain (unless the fix changes which flow matches — see §7.2). |

---

## 4. Snapshot-at-submission: the central design decision

Every field an approver's screen actually reads (`levelOrder`, `levelName`, `quorum`, resolved `approverId`) is **copied** onto a runtime row (`ApprovalLevelInstance`/`ApprovalAssignment`) the moment a report is submitted, via `ApprovalWorkflowServiceImpl.materializeChain()`. Nothing at runtime re-reads `ApprovalFlow`/`ApprovalLevel`/`ApprovalLevelApprover` — those are consulted exactly once, at resolution time.

Consequence: **an Admin editing a flow's levels, criteria, or approvers has zero effect on reports already in progress.** A flow can even be deleted (the non-catch-all delete path has no in-flight-report check at all) without disturbing anything already materialized, because the runtime rows never look back at the config rows again. The only way an in-flight report's chain changes is a resubmission after `NEEDS_CORRECTION` that happens to cause a *different* flow to match (§7.2) — and even then, that's a fresh resolution against current config, not a live update of the existing chain.

This is also why `ApprovalLevelInstance.flowId` is a bare UUID column, not a `@ManyToOne` — the runtime data must survive the referenced flow being deleted.

---

## 5. Flow resolution

`DefaultApprovalFlowResolutionServiceImpl.resolveMatchingFlow(report)` is called once per submission (and once per resubmission, to decide resume-in-place vs. full-restart — §7.2).

### 5.1 Matching algorithm

```
for each non-catch-all flow where status = ACTIVE, ordered by priority ascending:
    if flow.matches(report):
        return flow
return the catch-all flow  (throws ResourceNotFoundException if none has ever been configured)
```

Lowest `priority` number wins first. A flow with **zero criteria rows can never match** (`matches()` returns `false` immediately) — a deliberate guard against a misconfigured flow accidentally acting as a second catch-all.

### 5.2 The criteria pattern grammar

`ApprovalFlow.criteriaPattern` is a boolean expression over `ApprovalFlowCriterion.index` values, parsed and evaluated by a small hand-rolled recursive-descent parser (`common/CriteriaPatternEvaluator.java`):

```
expr   := term (OR term)*
term   := factor (AND factor)*
factor := NUMBER | '(' expr ')'
```

`AND`/`OR` are case-insensitive keywords with word-boundary matching (`"1ANDROID"` would not be parsed as `1 AND ROID` — the boundary check requires the next character not be alphanumeric). Standard precedence: `AND` binds tighter than `OR`, and parentheses can nest arbitrarily (the visual frontend builder only supports one level — OR-of-AND-groups — see §14.1; a hand-authored pattern using deeper nesting is still evaluated correctly by the backend, just not editable in the visual builder). Referencing an index not present in the flow's own criteria throws `IllegalArgumentException` both at config-save time (`assertCriteriaPatternValid`, called from `ApprovalFlowServiceImpl.create()`/`update()`) and — as a defensive check that should never actually trigger given that validation — at resolution time.

### 5.3 Per-field evaluation

Each `ApprovalFlowCriterion` has a `field` (`CriterionField`: `AMOUNT`/`CATEGORY`/`DEPARTMENT`/`COST_CENTER`), an `operator` (`CriterionOperator`: `EQUALS`/`NOT_EQUALS`/`GREATER_THAN`/`GREATER_THAN_OR_EQUAL`/`LESS_THAN`/`LESS_THAN_OR_EQUAL`), and a free-text `value`. **`GREATER_THAN`/`LESS_THAN` variants are only meaningful for `AMOUNT`** — `ApprovalFlowServiceImpl.assertCriteriaPatternValid` rejects any non-`AMOUNT` criterion using them at config-save time (also enforced client-side in the admin builder — see §14.1).

| Field | Evaluated against | Notes |
|---|---|---|
| `AMOUNT` | `report.totalAmount`, converted to base currency **using today's exchange rate**, not the report's submission-date rate | Non-numeric configured `value` logs a warning and is treated as no-match, not an error. |
| `CATEGORY` | **Any** line item's category code (`report.expenseLineItems.stream().anyMatch(...)`) | OR-aggregated across line items, computed fresh at every resolution — never persisted. Only `EQUALS`/`NOT_EQUALS` are meaningful. |
| `DEPARTMENT` | The **submitter's own** `EmployeeCache.departmentUuid` | Not the cost center's department — deliberately submitter-relative, consistent with `DEPARTMENT_OWNER` approver resolution also being submitter-relative. |
| `COST_CENTER` | `report.costCenter.costCenterCode` | Null-safe: no cost center on the report means this criterion is simply false. |

---

## 6. Approver source resolution

`DefaultApproverSourceResolverImpl.resolve(entry, report)` returns `Optional<String>` (an EOS employeeId) and **never throws** for a resolution failure — every failure path logs a warning and returns empty.

| Source type | Resolves via | On failure |
|---|---|---|
| `NAMED_USER` | `entry.sourceReference`, used as-is (no lookup at all) | N/A — config-time validation already requires a non-blank reference. |
| `REPORTING_MANAGER` | `EmployeeCache.managerEmployeeId` for the submitter | Empty + warning log if no manager on file. |
| `DEPARTMENT_OWNER` | The submitter's `EmployeeCache.departmentUuid` → `DepartmentApproverRepository.findByDepartmentUuid(...)` → `approverEmployeeId` | Empty + warning log if no `DepartmentApprover` mapping exists, or the department UUID string fails to parse. |
| `COST_CENTER_OWNER` | `report.costCenter.ownerEmployeeId` | Empty + warning log if the report has no cost center, or the owner field is blank. |

**Consequence at chain materialization** (`ApprovalWorkflowServiceImpl.materializeChain`): each entry that fails to resolve is simply skipped — no `ApprovalAssignment` row is created for it. If *every* entry at a level fails to resolve, the whole submission fails with `IllegalStateException` ("Level N of flow ... resolved zero approvers — check its approver-source configuration"). This is a real operational trap worth knowing: a level configured with only a `DEPARTMENT_OWNER` entry, for a department with no `DepartmentApprover` mapping, will block every submission that reaches that level until an Admin fixes the mapping.

---

## 7. Submission lifecycle

### 7.1 First submission

```
submit(reportId)
  ├─ assert status == DRAFT, has ≥1 line item, has a cost center
  ├─ PolicyEvaluationGateway.evaluate(report) — blocks if not allowed (§13)
  ├─ resolveMatchingFlow(report)                                  [§5]
  ├─ nextSubmissionCycle = currentSubmissionCycle + 1
  ├─ materializeChain(report, flow, cycle)                        [§4, §6]
  │     for each ApprovalLevel (ordered):
  │       create ApprovalLevelInstance (status=QUEUED)
  │       for each ApprovalLevelApprover (ordered by entryOrder):
  │         resolve → create ApprovalAssignment (status=PENDING)
  ├─ chainCorrectnessService.applyCorrectnessPasses(report, cycle) [§8]
  ├─ report.status = PENDING_APPROVAL, submittedAt = now
  └─ activateNextEligibleLevel(report, cycle, afterLevel=null)     [§9]
```

`activateNextEligibleLevel` finds the first `QUEUED` instance after the given level (or from the start), sets it `ACTIVE`, and activates its assignments per quorum:
- `SEQUENTIAL`: only the first (lowest `entryOrder`, non-`SKIPPED`) assignment becomes `ACTIVE`.
- `ANY_OF` / `ALL_OF`: every non-`SKIPPED` assignment becomes `ACTIVE` at once.

Activating an assignment stamps `assignedAt = now` and `dueDate = assignedAt + SLA business days` (§9) — **the SLA clock starts only at activation, never at materialization.** A fresh `ApprovalLineItemReview` (status `PENDING`) is created for every line item on the report, for the newly-active instance.

If no `QUEUED` instance remains (every level was skipped — see §8.2), the report goes straight to `APPROVED`.

### 7.2 Resubmission after Needs Correction

`submitReport()` on a report in `AWAITING_CORRECTION` takes a different path:

```
resubmitCorrection(report)
  ├─ PolicyEvaluationGateway.evaluate(report) — blocks if not allowed
  ├─ rematchedFlow = resolveMatchingFlow(report)   — re-run against current config + edited line items
  ├─ if rematchedFlow.flowId == the flow that produced the current cycle's instances:
  │     resumeInPlace(report, currentCycle)
  └─ else:
        fullRestart(report, rematchedFlow)
```

**Resume in place**: the currently-`ACTIVE` instance's `NEEDS_CORRECTION` reviews are reset to `PENDING` (approved lines are left alone — their outcome is preserved), report goes back to `PENDING_APPROVAL`. The same submission cycle continues; nothing is re-materialized.

**Full restart**: every instance of the *old* cycle that isn't already `COMPLETED` is marked `CANCELLED`, the cycle counter increments, and a brand-new chain is materialized (and correctness-passed, and activated from the start) against the new flow — because the employee's edit changed which flow now matches (e.g. they lowered the amount below a threshold, or changed category), the whole routing decision is redone from scratch. Levels that were already `COMPLETED` under the old flow are not carried forward or credited against the new one.

### 7.3 Line-item review — the real unit of approver action

`reviewLineItem(reportId, lineItemId, actingEmployeeId, request)` where `request.decision` is `APPROVED` or `NEEDS_CORRECTION` (never `PENDING` — rejected as a 400 if attempted), and `comment` is required for `NEEDS_CORRECTION`:

1. Find the report's currently-`ACTIVE` instance for the current cycle.
2. Find an `ACTIVE` assignment on that instance where `delegationService.canAct(actingEmployeeId, assignment.approverId)` — i.e. the caller either *is* that approver or is their current active delegate. No match → `AccessDeniedException`.
3. Find the `PENDING` review for that line item + instance. Already reviewed → `IllegalArgumentException`.
4. Set the review's `status`/`comment`/`actionedAt`; `actedBy` is set to the acting employee **only if they acted as a delegate** (left `null` when the approver acted directly — `actedBy` exists specifically to distinguish "who really clicked" from "whose assignment this was").
5. **If `NEEDS_CORRECTION`**: report status → `AWAITING_CORRECTION`. Stop — the level does not advance, and other line items at this level keep whatever review state they already had (an earlier `APPROVED` line stays approved; it isn't reset).
6. **If `APPROVED`** and every line item at this instance is now `APPROVED`: `completeLevelOrAdvanceSequential()` runs (§8).

### 7.4 Bulk approve

`bulkApprove(reportId, actingEmployeeId)` — only permitted when the report has **zero** policy violations anywhere (`PolicyViolationRepository.findByLineItem_Report_ReportId(reportId).isEmpty()`) and none of the currently-`PENDING` reviews' line items carry a violation either (a second, more specific check). If eligible, it simply calls `reviewLineItem(..., APPROVED, null)` in a loop for every `PENDING` review at the active instance — it is not a separate code path, just a convenience wrapper with an eligibility gate in front of it.

### 7.5 Whole-report Reject — terminal, distinct from Needs Correction

`rejectReport(reportId, actingEmployeeId, request)` requires an active, delegation-eligible approver at the current level (same authorization check as line-item review), then: cancels every non-`COMPLETED` instance of the current cycle, sets `report.status = REJECTED`, and stamps `rejectedBy`/`rejectionComment`/`rejectedAt`. **There is no resubmission path from `REJECTED`** — the employee must create an entirely new report. This is the key distinction from `NEEDS_CORRECTION`: the latter is a normal, expected loop back to the employee; rejection is a final business decision (fraud, duplicate, wrong report).

### 7.6 Recall and Cancel

Both are employee-initiated and share one restriction, computed by a single shared helper (`hasAnyLevelApproved`) so the enforcement and the read-model (§12) can never drift apart:

> **Blocked once any level in the current cycle has reached `COMPLETED`.**

- **Recall** (`PENDING_APPROVAL`/`AWAITING_CORRECTION` → `DRAFT`): cancels all open instances, returns the report to `DRAFT` for further editing before resubmission. Distinct from a correction resubmission — recall is employee-initiated and unconditional (no approver action required), whereas resubmission after `NEEDS_CORRECTION` is a response to a specific flag.
- **Cancel** (any non-terminal status except already-`APPROVED`/`REJECTED`/`CANCELLED` → `CANCELLED`): a terminal abandon, distinct from Recall (which returns to `DRAFT` for reuse). If the report was still `DRAFT`, no instances exist to cancel.

---

## 8. Chain correctness passes

Immediately after `materializeChain()` (both on first submission and on full restart), `ChainCorrectnessServiceImpl.applyCorrectnessPasses(report, cycle)` runs two passes over the freshly-materialized, not-yet-activated chain, **in this order**: self-approval first, duplicate-approver second — deliberately, "so de-dup operates on each assignment's final resolved approver, not a pre-substitution one."

### 8.1 Self-approval cascade

For every assignment across every instance, if the resolved `approverId` equals the report's own submitter, it is substituted **in place** (no new row, no status change) — the original id is preserved in `supersededApproverId` and `approverId` is overwritten. The replacement is resolved via a strict, three-step cascade:

```
1. delegationService.resolveActiveDelegate(submitterId)     — the submitter's own active delegate, if any
2. EmployeeCache.managerEmployeeId for the submitter          — filtered so the manager can't equal the submitter
3. SystemConfiguration key "approval.default-approver-employee-id"  — the org-wide Default Approver
```

If all three are exhausted, submission fails hard with `IllegalStateException` — a self-approval conflict is treated as a rule violation that must never be allowed to stand, not something an Admin resolves later. In practice, this means an org **must** configure the Default Approver key before self-approval can ever occur safely (e.g. the CEO submitting an expense where they'd also resolve as their own `COST_CENTER_OWNER`).

### 8.2 Duplicate-approver skip

Walking the same instances/assignments in level order, the first occurrence of a given `approverId` anywhere in the chain stands; every later occurrence (a later level, or a later entry within `ALL_OF`/`ANY_OF`) is set `SKIPPED`. This commonly happens in small teams where, say, the Reporting Manager and the Department Owner are the same person — without this pass, that person would be asked to approve the same report twice.

If every assignment at a level ends up `SKIPPED` (e.g. a level whose sole approver already appeared earlier), `activateNextEligibleLevel` simply moves past it — a fully-skipped level never becomes `ACTIVE` and contributes nothing to the SLA clock.

### 8.3 Documented simplification: `ALL_OF` and `ANY_OF` are currently identical

`ApprovalLineItemReview` is keyed by `(lineItem, levelInstance)` — one shared review row per line item per level, regardless of how many approver entries that level has. Under `SEQUENTIAL`, each `entryOrder` gets a fresh pass (reviews reset to `PENDING` when the next entry's turn starts — see `completeLevelOrAdvanceSequential`). Under `ANY_OF`, this is exact: "first entry to finish a full pass wins." Under `ALL_OF`, this is **not yet a true four-eyes control** — it behaves identically to `ANY_OF` today (the level completes as soon as one entry finishes a full pass), because a genuine "every approver independently agrees on every line item" semantics would require reviews keyed by `(lineItem, levelInstance, assignment)` instead of just `(lineItem, levelInstance)` — a real data-model change, left for a future enhancement if strict `ALL_OF` is actually needed. **If your organization is relying on `ALL_OF` for a genuine dual-control requirement, this gap matters — verify current behavior before depending on it.**

### 8.4 Level completion and sequential advancement

`completeLevelOrAdvanceSequential`, called once every line item at the active instance reaches `APPROVED`:

- **`SEQUENTIAL`** with a remaining `PENDING` entry: that next entry becomes `ACTIVE` (fresh `assignedAt`/`dueDate`), and every line item review at this instance is reset to `PENDING` for their pass. The level itself does not complete yet.
- **Otherwise** (no remaining `SEQUENTIAL` entry, or `ANY_OF`/`ALL_OF`): every remaining `ACTIVE`/`PENDING` assignment at this instance is force-completed, the instance itself is set `COMPLETED`, and `activateNextEligibleLevel` runs for the next level after this one. If none remains, the report is set `APPROVED` (`approvedAt` stamped) — this is the sole hand-off point to Reimbursement Tracking.

---

## 9. SLA and escalation

- **SLA window**: `SlaPolicyService.resolveSlaBusinessDays()` reads `SystemConfiguration` key `"approval.sla.business-days"`, falling back to a hardcoded default of **3** business days if the key is absent or fails to parse as an integer.
- **Business-day arithmetic** (`common/BusinessDayCalculator.java`): a simple day-by-day walk that skips Saturday/Sunday. **There is no holiday calendar anywhere in this codebase** — public holidays are not accounted for, a known and documented gap, not an oversight to hunt for elsewhere.
- **Escalation sweep**: `EscalationScheduler` runs `EscalationService.runReminderSweep()` on an hourly cron (`@Scheduled(cron = "${escalation.sla.cron:0 0 * * * *}")`, overridable via property). It queries every `ApprovalAssignment` with `status = ACTIVE` and `dueDate` before now, and for each one publishes an `"SLA_REMINDER"` domain event. **That is the entire effect** — no status changes, no reassignment, nothing else happens. This is deliberate: "reminders only... never auto-reassigned," a direct contrast with the old EP06 engine's auto-skip-to-manager escalation. An overdue assignment stays exactly where it is until the approver acts, sets a delegate, or an Admin intervenes manually.

---

## 10. Delegation

`ApprovalDelegation` is a completely separate, always-consulted layer — assignments are never rewritten when a delegation starts or ends. The one rule, evaluated fresh on every action:

> `canAct(actingUser, assignment) = actingUser == assignment.approverId OR an ACTIVE delegation exists where delegatorId == assignment.approverId AND today is between startDate and endDate`

If two non-cancelled delegations for the same delegator both cover today (an overlap — `ApprovalDelegationServiceImpl.warnOnOverlap` logs a warning at creation time but does not block it), **the most-recently-created one wins** — `resolveActiveDelegate` picks the max by `createdAt` among in-window, non-cancelled rows.

### 10.1 Authorization: self-service, not role-gated

Both the controller (`ApprovalDelegationController`) and the service layer deliberately carry **no role restriction** on create/update/delete — any authenticated employee can set their own delegate, because any employee can be a resolved approver via `NAMED_USER`/`DEPARTMENT_OWNER`/`COST_CENTER_OWNER`, not just people with a "Manager" role. Authorization is instead an ownership check inside `ApprovalDelegationServiceImpl.assertSelfServiceOrAdmin`: a non-`ADMIN` caller may only act on a delegation where they are the delegator themselves; `ADMIN` may act on anyone's. On `update`, the check runs against *both* the request's `delegatorId` and the existing row's `delegatorId`, closing off a reassignment trick (a non-admin trying to hijack someone else's delegation by editing it to point at themselves).

### 10.2 Resolving "who can I act for" without an N-query scan

`DelegationService.resolveApproverIdsActingFor(actingEmployeeId)` — the caller's own employeeId, plus every delegator for whom the caller is *currently* the winning active delegate (re-applying the same overlap tie-break, so a delegator whose newer delegation now names someone else is correctly excluded). This backs the paginated "My Queue" endpoint's single `approverId IN (...)` query (§12.1) rather than loading every candidate assignment into memory and filtering row-by-row.

---

## 11. Security model

- **Every endpoint requires a valid JWT** issued by UMS, validated as an OAuth2 resource server (`SecurityConfig`); `JwtAuthConverter` maps the `roles`/`permissions` claims to Spring Security authorities.
- **Action endpoints carry no URL-level role gate** (`ApprovalWorkflowController`: submit/recall/cancel/review/reject/bulk-approve, and `ApprovalDelegationController`'s mutations) — authorization is a per-task, per-assignment ownership check inside the service layer (`delegationService.canAct(...)`), not a role. This is a deliberate, repeated pattern in this system, matching the reality that "who can approve" is a data fact (who resolved), not a role fact.
- **`ApprovalFlowController` and `DepartmentApproverController` are the exception**: both carry a class-level `@PreAuthorize("hasRole('ADMIN')")` — configuring the routing rules themselves genuinely is admin-only.
- **`ApprovalDelegationController`'s GET endpoints** require `hasAnyRole('ADMIN','MANAGER','FINANCE','GENERAL')` — effectively every authenticated role, since `GENERAL` is this system's normal-employee role (see §14.5).

---

## 12. Read models and the pagination convention

### 12.1 `PageResponse<T>`

A generic envelope (`dto/response/PageResponse.java`): `{ content, page, size, totalElements, totalPages, first, last }`. This is the **first** server-side pagination convention established anywhere in this backend — no other module paginates at the database level; everything else returns a full list. It backs two endpoints:

- **`GET /xms/approvals/my-queue?page=&size=`**: resolves `resolveApproverIdsActingFor(caller)`, then a single query (`ApprovalAssignmentRepository.findDistinctReportIdsByStatusAndApproverIdIn`) groups by report id and orders by `MIN(assignedAt)` (oldest-assigned first) — a `GROUP BY` + aggregate rather than `DISTINCT` + `ORDER BY` on a non-selected column, which some JPA providers reject. Each page's report ids are then hydrated into full `ApprovalQueueItemResponse` rows.
- **`GET /xms/approvals/my-history?outcome=&page=&size=`**: a single JPQL query on `ExpenseReport` with an `OR` across both outcome branches (an `EXISTS` against a `COMPLETED` assignment for `APPROVED`; a `rejectedBy` match for `REJECTED`) — no `UNION` needed, since both branches are predicates against the same base entity. Ordered by `COALESCE(approvedAt, rejectedAt)` descending. **Known simplification**: this does not (yet) attribute a delegate's acted-on-behalf-of decisions back to the delegate — only the originally-resolved `approverId`'s `COMPLETED` assignments count toward "my approved history."

### 12.2 Other read endpoints

- **`GET /xms/approvals/{reportId}/status`** → `ApprovalStatusResponse` (`currentLevelOrder`, `currentLevelName`, `currentLevelDisplayName`, `totalLevels`, `canRecall`, `canCancel`). Deliberately a separate endpoint rather than fields bolted onto `ExpenseReportResponse`, so the plain CRUD `ExpenseReportServiceImpl` never has to know about Approval entities at all. `canRecall`/`canCancel` call the exact same eligibility logic that `recall()`/`cancel()` themselves enforce (§7.6) — they can never drift apart.
- **`GET /xms/approvals/{reportId}/line-item-reviews`** → `LineItemReviewResponse[]` for the current submission cycle, visible to the report's owner or anyone who has ever been (or is a delegate of) an assignee on it.

### 12.3 Display-name fallback

`ApprovalFlowMapper.resolveDisplayName(levelName, levelOrder)` — a one-line static helper reused across the config side (`ApprovalLevelResponse.displayName`) and the runtime side (`ApprovalStatusResponse.currentLevelDisplayName`, `LineItemReviewResponse.displayName`): if `levelName` is null/blank, falls back to `"Level " + levelOrder`. The fallback is computed at the DTO layer only — never stored.

---

## 13. Policy Engine integration

`ApprovalWorkflowServiceImpl.submit()` calls `PolicyEvaluationGateway.evaluate(report)` and blocks submission if `!decision.allowed()`. This is a deliberate seam: the Approval Engine reacts only to `PolicyDecision.allowed()`, never to specific rule types or severities, so the Policy Engine can evolve independently.

**As of this writing, the wiring on the other side of that seam has not caught up with the Policy Engine's own capabilities.** `InterimPolicyEvaluationGatewayImpl` re-runs `PolicyEvaluator` across every line item and **always returns `allowed = true`** — it was written when the Policy Engine had no real blocking tier. The Policy Engine, since merged from a parallel branch, now has a genuine `PolicyEnforcementType.BLOCK` (`PolicyRule.enforcementType`, stamped onto every `PolicyViolation` by `DefaultPolicyEvaluator`) — but nothing in the current codebase actually checks for it outside test code (confirmed by grep: zero non-test references to `PolicyEnforcementType.BLOCK`). **A BLOCK-severity policy violation today does not block submission** — it's flagged, exactly like a WARN, and the employee can submit anyway. This is a real, currently-open integration gap, not a documented simplification the team has accepted — see the note left in the previous session's history for the concrete one-file fix (`InterimPolicyEvaluationGatewayImpl.evaluate()` should check for any recomputed violation with `enforcementType == BLOCK` and set `allowed = false`).

---

## 14. Real-time updates

Two independent transports exist, serving different purposes — this is worth being precise about, since they're easy to conflate.

### 14.1 RabbitMQ — durable, currently unconsumed

`RabbitApprovalEventListener` is a `@TransactionalEventListener(phase = AFTER_COMMIT)` that publishes every `ApprovalDomainEvent` (`eventType`, `reportId`, `detail`, `occurredAt`) to a topic exchange (`approval.events`, routing key `approval.<lowercased-event-type>`), fire-and-forget — a broker outage never affects the engine's own transaction, which has already committed by the time this runs. **`ApprovalEventingConfig`'s own class comment states plainly: "Consumers (Notification/Audit) are explicitly out of scope for now... this queue exists so the transport is real and durable from day one, even though nothing consumes it yet."** There is no email/push notification service and no dedicated append-only audit log wired to this system today — the closest things to an audit trail are the persisted (and mutated-in-place) assignment/review rows themselves, plus ordinary `log.info` application logging.

### 14.2 WebSocket — live, browser-facing

A second, independent consumer of the same `ApprovalDomainEvent` stream: `ApprovalWebSocketEventListener` (another `AFTER_COMMIT` sibling listener, same fire-and-forget shape) pushes to two per-user STOMP destinations via `SimpMessagingTemplate.convertAndSendToUser`:

- `/queue/report-updates` → the report's owner (drives the employee's status pill / correction-visibility live refresh).
- `/queue/approval-queue-updates` → every currently-`ACTIVE` assignment's resolved `approverId` (drives the approver's queue live refresh).

**Documented simplification**: delegates are not pushed to directly — only the originally-resolved `approverId`. A delegate's queue is always resolved fresh from the database on their next fetch (§12.1's `resolveApproverIdsActingFor`), so this is a live-refresh nicety, not a correctness gap; pushing to every possible delegate of every assignment on every event was judged not worth the extra resolution cost.

**Transport details**: `ApprovalWebSocketConfig` registers a STOMP-over-SockJS endpoint at `/xms/ws` (this service bakes its `/xms` prefix into every mapping rather than relying on a gateway to strip it — matching its own existing convention). `ApprovalWebSocketAuthInterceptor` is a same-shape copy of the Leave Management Service's proven `AuthChannelInterceptor`, adapted to this service's own JWT claim (`employee_id`, not email/subject — this matters because `convertAndSendToUser`'s target string must match the STOMP principal's name exactly): CONNECT requires a valid JWT, SUBSCRIBE is restricted to the caller's own `/user/queue/*` (an explicit `/user/{otherEmployeeId}/queue/*` attempt is blocked), and client SEND is restricted to `/app/*` (nothing in this service currently has an `@MessageMapping`, so no client SEND is ever legitimate — this closes off a client injecting phantom events onto the broker).

**Why two separate WebSocket connections exist in the browser**: this service runs as its own independent Spring Boot process (separate port in dev, separate `/xms` path in prod), so it cannot share the Leave Management Service's in-JVM `SimpleBroker` connection — `ApprovalWebSocketProvider` on the frontend is a second, independent STOMP client, not a reuse of the existing one.

### 14.3 The Policy Engine merge and a Flyway collision

Worth recording here since it's a real incident, not a hypothetical: merging the separately-developed Policy Engine/OCR branch brought in its own `V6`–`V10` migrations, colliding with this engine's pre-existing `V6__approval_flow_engine_cleanup.sql` (Flyway requires unique version numbers). The collision was caught mid-merge and resolved by renumbering to `V11`, with no content change — see commit history for `31406dd`.

### 14.4 Frontend integration point summary

For anyone tracing a live update end-to-end: `ApprovalWorkflowServiceImpl` mutates state inside a transaction → publishes `ApprovalDomainEvent` → on commit, `ApprovalWebSocketEventListener` pushes to the relevant per-user queues → the frontend's `ApprovalWebSocketProvider` (a STOMP client mounted once at the app root, alongside — not replacing — the existing Leave Management one) re-emits a named browser-side event (`"report-update"` / `"queue-update"`) → `useApprovalLiveSync()` (a small hook mounted wherever needed) subscribes to those and calls `queryClient.invalidateQueries(...)` on the relevant TanStack Query cache keys, triggering a silent refetch.

### 14.5 Role naming: `GENERAL` is this system's "Employee"

Worth flagging for anyone confused by the role strings: this system's normal, non-privileged employee role is literally named `"General"` (not `"Employee"`) throughout — `ApproverSourceType`/role checks/the sidebar config all key off `General`. `Manager`/`Finance`/`Admin`/`Super_Admin` are the other XMS-relevant roles. This is a pre-existing platform convention, not something introduced by this engine.

---

## 15. Frontend architecture

Located at `src/pages/expense-management/approval-engine/` in `intranet-fe` — a self-contained module, not woven through the pre-existing `expense-management` pages except at specific, deliberate integration points (see below).

### 15.1 Libraries

| Concern | Library | Note |
|---|---|---|
| Server state / caching | `@tanstack/react-query` | Already installed and used elsewhere in this app (`Projects/MyWork`, `accounts-payable`); this module adopts the same convention (query-key factory functions, `useQuery`/`useMutation`/`useQueryClient`). |
| Drag-and-drop | `@dnd-kit/core` + `@dnd-kit/sortable` | New dependency, used only in the Admin flow builder (reordering levels, approver entries, and criteria/OR-groups). Two other DnD libraries (`@hello-pangea/dnd`, `react-dnd`) already existed in this app unused by this module; `@dnd-kit` was chosen specifically for the nested criteria-group case. |
| Real-time | `@stomp/stompjs` + `sockjs-client` | Already installed for the Leave Management Service's own WebSocket; this module adds a second, independent client instance (§14.2). |
| Data grids | Plain HTML `<table>` markup | `@tanstack/react-table` is installed but deliberately **not** used — the installed major version (v9) ships a materially different API (`useTable`/`useLegacyTable`, not the `useReactTable` most existing documentation/training describes) than what most engineers coming to this codebase will expect, and this module's actual grid needs (no client-side sorting/filtering, since pagination is server-side) didn't justify the risk of building against an unfamiliar API under time pressure. Worth revisiting if a future screen genuinely needs client-side sorting/filtering/virtualization. |
| Forms | `react-hook-form` (flat fields) + plain `useState` (the two dnd-kit-driven nested builders) | The flow builder's levels/criteria arrays are managed as plain state and passed to the dnd-kit components via `onChange` callbacks, rather than `react-hook-form`'s `useFieldArray` — the reorder logic (`arrayMove`) operates on plain arrays. |

### 15.2 Module structure

```
approval-engine/
├── api/            — axios calls (approvalWorkflowApi, approvalFlowApi, departmentApproverApi, approvalDelegationApi)
├── hooks/          — TanStack Query hooks per resource, + useApprovalLiveSync (WebSocket → cache invalidation glue)
├── websocket/       — ApprovalWebSocketProvider (a second STOMP client, see §14.2)
├── components/      — ApprovalStatusPill, CommentPromptModal, LineItemReviewPanel, CriteriaBuilder,
│                       LevelsBuilder, MyDelegateCard
├── pages/            — PendingApprovalsPage, ApprovalHistoryPage, ApprovalFlowsPage,
│                        ApprovalFlowBuilderPage, CatchAllFlowPage, DepartmentApproversPage, DelegationsPage
└── utils/            — criteriaPattern.js (DNF ⇄ criteriaPattern string, see §15.3)
```

### 15.3 The criteria builder's supported subset

The backend's grammar (§5.2) allows arbitrary nesting. `utils/criteriaPattern.js` supports round-tripping only **disjunctive normal form** — OR of AND-groups, e.g. `(1 AND 2) OR 3` — since that covers every realistic admin rule without a full expression-tree editor. `parseCriteriaPattern` returns `null` (rather than guessing) when an existing flow's pattern doesn't fit this shape (nested parens beyond one level); `ApprovalFlowBuilderPage` falls back to a raw-text pattern field plus a flat, ungrouped list of the flow's criteria in that case, rather than silently mangling a hand-authored pattern.

### 15.4 Integration points with the pre-existing app

Deliberately minimal and explicit, per the module's design ownership:

- **Sidebar**: a new "Approval Rules" section (`sidebarConfig.js`), nested inside the existing Expense Management entry — mirroring the "Policy & Compliance" section's own precedent, ADMIN-gated (`XMS_ADMIN`). The Approver-facing "Approvals" section (Pending/Approved/Rejected) already existed as a nav entry from a prior session; this work replaced its placeholder pages and **broadened its role gate from `Manager`-only to every authenticated employee** (§14.5 — any employee can be a resolved approver, so a `General`-role approver needs a way in too; the route-level `ProtectedRoute` gate had the same bug and was fixed alongside it).
- **`main.jsx`**: `ApprovalWebSocketProvider` mounted at the app root, alongside (not replacing) the existing Leave Management `WebSocketProvider`.
- **`AuthContext.jsx`**: syncs the new provider's token on login/logout (additive only — the pre-existing Leave Management token-sync wiring, which has its own pre-existing gap around not syncing on `login()`, was left untouched as out of scope).
- **`ExpenseReportDetailPage.jsx` / `MyExpensesPage.jsx`**: the employee-facing status pill, correction banner, and Submit/Resubmit/Recall/Cancel actions were added into these two pre-existing pages (not new pages) — the status display was switched from the shared, generic `StatusBadge` component to this module's own `ApprovalStatusPill`, since `StatusBadge`'s keyword-matching does not recognize `AWAITING_CORRECTION` and renders it as an uncolored default.

---

## 16. Consolidated list of known limitations

For anyone doing an operational readiness review, everything flagged as a gap above, in one place:

1. **`ALL_OF` quorum is not a true four-eyes control** — behaves identically to `ANY_OF` (§8.3).
2. **Policy Engine BLOCK enforcement is not wired to submission** — a BLOCK-severity violation does not actually block (§13).
3. **No notification or audit-log consumer** on the RabbitMQ transport — events are published and durably queued, but nothing reads them (§14.1).
4. **No holiday calendar** — SLA due dates only skip weekends (§9).
5. **Escalation is reminder-only** — an overdue assignment is never auto-reassigned; a human must set a delegate (§9).
6. **"My History" doesn't attribute delegate-acted decisions to the delegate** — only the originally-resolved approver's completed assignments count (§12.1).
7. **Live WebSocket pushes don't reach delegates directly** — their queue is refreshed on next fetch instead (§14.2).
8. **`AssignmentStatus.SUPERSEDED` and the account-removal reassignment scenario its `supersededApproverId` field comment describes are not implemented** — that field is currently only ever set by the self-approval substitution pass (§2.2, §8.1).
9. **A misconfigured `DEPARTMENT_OWNER`-only level with no matching `DepartmentApprover` mapping blocks every submission that reaches it** with an `IllegalStateException`, not a friendlier admin-facing warning (§6).
