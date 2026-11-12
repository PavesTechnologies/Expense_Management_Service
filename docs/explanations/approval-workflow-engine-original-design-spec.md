# Approval Workflow Engine — Feature & Design Document

**Status:** Core decisions locked; details still open (see §15)
**Replaces:** The existing matrix-based approval engine (internally referred to as "EP06" in code) — a full rewrite, not an extension. EP06 was found to be a complete, tested implementation, not a stub; see §16 for exactly what it did and which of its mechanics this design deliberately keeps.
**Owner:** TBD
**Last updated:** 2026-08-06

---

## 1. Purpose & Business Rationale

The Approval Workflow Engine answers one operational question automatically: **"Who needs to sign off on this expense, in what order, and has that happened yet?"**

Without it, that coordination happens through memory, hallway conversations, or email chains — slow, untraceable, and inconsistent between teams. The engine makes routing, sequencing, and sign-off **automatic, consistent, and fully auditable**, while keeping every actual approval decision in human hands — **the engine orchestrates, it never decides.**

It sits directly downstream of **Policy & Compliance** (which has already attached any violations by the time an expense arrives here) and directly upstream of **Reimbursement Tracking** (which only begins once every required approval is complete).

This document supersedes the old approval workflow. The old workflow is being removed; this is a ground-up replacement, not an extension of it — the mapping model in particular (cost-center + amount matrix) is being discarded in favor of a per-employee model (§4). A handful of proven mechanics from the old engine are deliberately carried forward as ideas, not code — see §16.

---

## 2. Core Concepts / Glossary

| Term | Definition |
|---|---|
| **Approval Step** | One stage in the chain (e.g., "Manager," "Finance"). A step may be conditional — it only runs if a trigger (amount/category) is met. |
| **Approver Mapping** | How the system determines who approves for a given employee: **Individual > Group > Default** — the same precedence structure as Policy Assignment. "Individual"/"Group" assignments can themselves resolve to a fixed person *or* to "the employee's reporting manager" (see below) — Admin picks per mapping. |
| **Reporting Manager** | A real, available data point — this employee's manager as recorded in the HR system of record, kept in sync locally. Not assumed absent; usable both as an Approver Mapping target and as the self-approval escalation target (§6). |
| **Sequential / Parallel-Any / Parallel-All** | When a step has multiple approvers: Sequential = one after another in order; Parallel-Any = any one of them can act, first action completes the step; Parallel-All = every one of them must act before the step completes (a genuine "four-eyes" control, not just "faster than sequential"). |
| **Delegate** | A stand-in approver, set either by the original approver themselves or directly by Admin, covering unavailability. |
| **Resolved Chain** | The specific, locked-in sequence of steps calculated for one expense at submission time, based on its amount/category. Does not change even if step configuration is edited later. |
| **Clarification State** | A non-terminal state where an approver has asked the employee a question without approving or rejecting. |
| **Duplicate-Approver Skip** | If the same person resolves as approver at two different steps in one chain (common in small teams), the second occurrence is auto-skipped rather than asking them to approve their own earlier decision again. |

---

## 3. Architecture Overview

Five logical components:

- **Approver Mapping Service** — resolves who approves for a given employee (Individual > Group > Default), and separately resolves delegate substitution when needed.
- **Approval Workflow Engine** — the orchestrator. Evaluates Conditional Approval Steps against a specific expense's amount/category to build the Resolved Chain, then tracks progress through it.
- **Delegation Resolver** — checked at every step hand-off: is the intended approver the same as the submitter (self-approval)? Do they have an active delegate (self-set or Admin-set)? Redirects accordingly before notifying anyone. Self-approval specifically cascades automatically (delegate → reporting manager → Default Approver) rather than waiting on a human — see §6.
- **Notification Service** — fires asynchronously on every state change; fully decoupled so workflow progress never depends on notification delivery succeeding.
- **Audit Service** — permanently records every step, decision, comment, and delegate substitution; visible to the employee as well as Admin/Finance.

```
                        ┌───────────────────────────┐
                        │   Policy & Compliance       │
                        │   (violations already        │
                        │    attached upstream)         │
                        └──────────────┬────────────────┘
                                       │ expense submitted
                                       ▼
   ┌───────────────────────────────────────────────────────────────────┐
   │                     APPROVAL WORKFLOW ENGINE (orchestrator)          │
   │                                                                     │
   │   builds Resolved Chain ──► tracks current step ──► advances/       │
   │                                                        completes    │
   └───────┬───────────────────────┬───────────────────────┬─────────────┘
           │ who approves?         │ notify                │ log
           ▼                       ▼                       ▼
 ┌──────────────────┐   ┌────────────────────┐   ┌────────────────────┐
 │ Approver Mapping   │   │ Notification Service │   │  Audit Service      │
 │ Service            │   │ (async, decoupled —  │   │ (immutable log,     │
 │ Individual > Group  │  │  fire-and-forget)    │   │  employee-visible)  │
 │ > Default            │  └────────────────────┘   └────────────────────┘
 └─────────┬──────────┘
           │ checked at every hand-off
           ▼
 ┌────────────────────────────────────────────────────────────────────┐
 │ Delegation Resolver — self-approval cascade (see §6 for full detail)  │
 │                                                                        │
 │ resolved approver == submitter? ──► no ──► proceed with resolved       │
 │           │                                approver                    │
 │           yes                                                          │
 │           ▼                                                            │
 │ active delegate for that approver? ──► yes ──► route to delegate        │
 │           │                                                             │
 │           no                                                            │
 │           ▼                                                             │
 │ approver's reporting manager on file? ──► yes ──► route to manager       │
 │           │                       (re-check: is the manager ALSO the      │
 │           no                       submitter? if so, skip straight to     │
 │           ▼                        Default Approver — never loop)         │
 │ route to Default Approver                                                  │
 └──────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Configuration

**Who configures:**
- Admin — approver mapping and step design.
- Individual approvers — can additionally set their own delegate.

**Approver Mapping** mirrors the Policy Assignment Model exactly: **Individual Assignment > Group Assignment > Default Approver.** Admin manually maps this — deliberately, since target customers (mid-sized companies) don't reliably have a formal, admin-maintained org chart to drive routing decisions off of.

That said, one piece of real org-structure data *is* already available and reliable: each employee's **reporting manager**, kept in sync from the HR system of record. This isn't a substitute for Individual > Group > Default (Admin still owns and drives that mapping explicitly), but it's a legitimate resolution target within it — an Individual or Group assignment can point to a fixed person, *or* to "this employee's reporting manager" — and it's the backbone of the self-approval cascade (§6).

**Approval Steps** are built as an ordered list. Each step has:
- An optional **trigger condition** (amount threshold and/or category, combinable) — if unmet, the step is skipped entirely for that expense.
- One or more **approvers**, with **Sequential, Parallel-Any, or Parallel-All** behavior configurable per step by Admin. Admin can mix — e.g., Step 1 sequential, Step 2 parallel-all — building any shape of approval tree they want, not a fixed pattern. Parallel-All exists specifically for "both must sign off" controls (e.g., dual Finance approval above a threshold); Parallel-Any is the "whoever's free first" case.
- Duplicate-approver protection: if the same person would be asked to approve twice in one chain, the later occurrence is auto-skipped.

**Delegation** is configured two ways, both pointing to the same underlying mechanism, whichever is currently active is used:
- An approver sets their own delegate (with an optional date range).
- Admin sets a delegate directly on any approver — Admin doesn't need to wait for or rely on the approver to act.

---

## 5. End-to-End Processing Flow

1. **Expense/report submitted** — arrives after the Policy Engine has already attached any violations. Block-type violations never reach this engine at all.
2. **Resolved Chain built** — the engine evaluates each configured step's condition against this expense's amount/category, locking in the exact sequence that applies. This locked sequence doesn't change even if step configuration is edited later.
3. **First step's approver resolved** — Approver Mapping Service determines who, then Delegation Resolver checks for self-approval (cascading automatically through delegate → reporting manager → Default Approver, §6) or an active delegate on the final resolved approver, redirecting if needed.
4. **Approver notified** (event-driven, asynchronous).
5. **Approver acts** — Approve (silently, even on a flagged/Warn-type expense), Reject (with an optional, gently-prompted comment), or Ask Clarification.
6. **On Approve** — if more steps remain in the Resolved Chain, repeat from step 3 for the next one. If this was the last step, report status becomes **Approved**.
7. **On Reject** — report unlocks for the employee to edit in place; on resubmission, the entire chain **restarts from step 2** (amount/category may have changed, potentially changing which steps even apply).
8. **On Clarification** — report sits in a non-terminal **"pending employee response"** state; the employee's reply returns it to the same approver's queue. An employee editing the expense while clarification is pending is also treated as their response.
9. **On full approval** — handed to Reimbursement Tracking; this engine's job for the report is done.
10. Every transition at every step is written to the **Audit Service**, visible to the employee.

**State machine:**

```
                         ┌────────────────────────────────────────────┐
                         │                                              │
                         ▼                                              │ resubmit
   ┌───────┐  submit  ┌───────────┐ resolve  ┌──────────────────┐  reject │ (chain restarts,
   │ Draft │─────────►│ Submitted │─────────►│ Under Approval     │────────┘  step 4 re-run)
   └───────┘          └───────────┘  chain   │ (Step N of M)       │
                                              │                     │
                                              │  ask clarification  │
                                              │  ◄───────────────┐  │
                                              │                  │  │
                                              │  ┌─────────────┐ │  │
                                              │  │ Pending      │ │  │
                                              │  │ Employee     │─┘  │
                                              │  │ Response     │    │
                                              │  └─────────────┘    │
                                              │       ▲ reply       │
                                              └───────┴──────┬──────┘
                                                              │ approve, more steps remain
                                                              │ (loop back to step 3 for
                                                              │  next step's approver)
                                                              │
                                                approve, last step
                                                              │
                                                              ▼
                                                       ┌────────────┐
                                                       │  Approved   │
                                                       └──────┬─────┘
                                                              │ handoff
                                                              ▼
                                                ┌───────────────────────┐
                                                │ Reimbursement Pending   │
                                                └───────────┬───────────┘
                                                            ▼
                                                        ┌───────┐
                                                        │  Paid  │
                                                        └───────┘

   Reject at any step ──► employee edits in place ──► Resubmit ──► restarts fully
                                                                    from Resolved-Chain
                                                                    build (step 2)
```

---

## 6. Decision & Resolution Logic — The Details

- **Conditional step evaluation:** each step's trigger (amount and/or category) is evaluated **once, at submission**, against that specific expense — not recalculated later, consistent with how Policy versions are locked at submission time.
- **Self-approval handling — fully automatic, never stalls, never waits on Admin:** checked at **every step hand-off**, not just once (an earlier delegate substitution in the chain must not bypass this check later). If the resolved approver is the submitter:
  1. Check that approver's active delegate. If one exists, route to the delegate.
  2. Otherwise, auto-escalate to that approver's own **reporting manager** and route to them instead.
  3. If that manager is *also* the submitter (or no manager is on file), skip straight to the **Default Approver** — never loop, never require Admin to step in.

  This is deliberately more automatic than the SLA-breach reminder behavior below: a self-approval conflict is a hard rule violation that must never be allowed to stand, whereas a slow approver is just a scheduling problem a human should stay in control of resolving.
- **Delegate resolution order:** an approver's self-set delegate and an Admin-set delegate both point to the same underlying mechanism — whichever is currently active is used; Admin's assignment doesn't require the approver's cooperation.
- **Bulk approval scope:** available for reports with **no policy flags**; flagged reports are deliberately excluded from easy bulk selection, so a human still looks at each one individually — a UX safeguard, not a hard backend restriction.
- **Granularity:** approval acts on the **whole report**, not individual line items — deliberately, to avoid partial-reimbursement complexity that Reimbursement Tracking doesn't currently support. (Line-item detail can still be *shown* on the review screen; the action itself is report-level.)

---

## 7. Exception Handling

| Scenario | Behavior |
|---|---|
| Approver has no delegate and is unavailable | Report waits in "Under Approval"; reminder notifications fire on schedule; visible to Admin as aging. Never a silent failure, but never auto-resolved either — a human (the approver or Admin) must set a delegate to move it. |
| Resolved approver is the submitter (self-approval) | Auto-cascades: delegate → reporting manager → Default Approver (§6). The only exception path in this engine that resolves itself without a human. |
| Parallel-Any: one of several approvers acts | That step completes immediately; the other(s) never need to act — no idempotency conflict, this is normal completion. |
| Parallel-All: two approvers act near-simultaneously | Idempotent handling ensures each action is recorded independently (both are expected in an ALL step); a genuine double-submit of the *same* action is deduplicated so it can't be double-processed. |
| Employee edits an expense while clarification is pending | Treated as their response; returns to the approver's queue automatically. |
| An approver's account is removed while they have pending approvals | Falls back to the Default Approver, so nothing is orphaned. |
| Step configuration edited mid-flight | The already-Resolved Chain for an in-progress report is unaffected; only new submissions use the updated configuration. |

---

## 8. Integration With Other Modules

| Module | Relationship |
|---|---|
| Policy & Compliance → Approval Workflow | Violations are already attached before an expense enters this engine; Block-type violations never reach it at all. |
| Approval Workflow → Approver Mapping / Delegation | Queried at every step hand-off to resolve the actual acting approver. |
| Approval Workflow → Notification Service | Every state change emits an event; delivery is fully decoupled from workflow progress. |
| Approval Workflow → Audit Service | Every step, decision, comment, and delegate substitution is permanently logged, visible to the employee. |
| Approval Workflow → Reimbursement Tracking | Triggered only once the full Resolved Chain is completed. |

---

## 9. Real-World Example Walkthroughs

**A — Conditional routing:** Employee submits a ₹3,000 office supply expense. Only the "Manager" step's condition is met; "Finance" (triggered only above ₹10,000) is skipped. One approval, done.

```
 Configured steps (Admin-defined, generic):
   Step 1: Manager           — no condition (always runs)
   Step 2: Finance           — condition: amount > ₹10,000

 ┌─────────────────────────────┐        ┌──────────────────────────────┐
 │ Expense: ₹3,000 (Office)      │        │ Expense: ₹12,000 (Hotel)       │
 └───────────────┬───────────────┘        └────────────────┬───────────────┘
                 ▼                                          ▼
       Resolved Chain:                             Resolved Chain:
       [ Manager ]                                 [ Manager ] → [ Finance ]
                 │                                          │
                 ▼                                          ▼
           Manager approves                          Manager approves
                 │                                          │
                 ▼                                          ▼
         status → Approved                          Finance approves
                                                              │
                                                              ▼
                                                     status → Approved
```

**B — Self-approval redirect:** A Manager submits their own ₹4,000 travel expense. The engine detects they're also the resolved approver, checks their delegate — none set — and automatically routes the step to the Manager's own reporting manager instead. No Admin involvement needed; the report simply moves forward with a different approver than the mapping would normally have picked.

**C — Rejection and restart:** An employee's ₹12,000 hotel expense is rejected by Finance for missing justification. They edit the amount down to ₹9,000 while fixing it — on resubmission, the chain restarts from step 2, and since ₹9,000 no longer meets Finance's ₹10,000 trigger, this time it only needs Manager approval.

**D — Admin-assigned delegate:** A Manager goes on leave without setting a delegate themselves. Admin directly assigns their Finance counterpart as a two-week delegate — every pending and new approval for that Manager silently redirects for the duration, no action needed from the Manager at all.

---

## 10. Frontend / User Experience

### Employee journey
- **Submitting:** camera/upload → OCR auto-fills fields → confirm → done. No manual routing decision, no "select your approver" step — the system already knows who approves, invisibly.
- **Building a report:** select expenses → "Create Report" → submit. Policy violations shown inline as a plain-language note *before* submission (Warn doesn't block).
- **After submission:** the golden rule — employees never check status manually. Push/in-app notification the moment a decision is made; a single always-visible status pill on the report ("Pending Manager Approval," "Awaiting Reimbursement").
- **On rejection:** reason (if given) surfaces directly in the notification; report opens with the flagged item pre-highlighted; "Resubmit" reuses the same creation screen.
- **On clarification:** appears like a chat message on the expense; reply inline, no separate resubmit needed.

### Approver journey (Manager / Finance)
- **Approval queue is the homepage**, not a menu item — nothing to navigate to find it.
- **Per-item review:** receipt image, extracted fields, and policy flags all on one screen. Three always-visible actions: **Approve, Reject, Ask Clarification.** No edit button, by design — keeps the action set to three clear choices.
- **Bulk approval:** checkbox-select multiple no-flag reports → "Approve Selected." Flagged reports excluded from bulk selection by default.
- **Rejecting:** optional comment field with a gentle prompt, and a visible "Skip."
- **Delegate setting:** one toggle in approver settings — "I'm out until [date], delegate to [person]."

### Admin journey
- Same **Individual > Group > Default** three-tier pattern for both approver mapping and policy assignment — one mental model learned once, reused everywhere.
- **Configuring approval steps:** visual builder — add a step, optionally attach a condition (amount/category), pick approver(s), choose sequential/parallel. Should feel like building a flowchart, not filling a form with hidden logic.

### UX rules
- Never ask for information the system can infer (OCR fields, currency, approver).
- One obvious primary action per screen.
- Status always visible without a click.
- Bulk actions wherever a user would otherwise repeat the same action many times.
- Rejection/clarification reasons travel forward automatically — employee never hunts for "why."

---

## 11. Backend Architecture

### Core services

| Service | Responsibility |
|---|---|
| Expense Capture Service | Receives submissions, coordinates OCR, stores expense records |
| Policy Engine | Resolves the employee's policy, evaluates rules, returns violations (already designed) |
| **Approval Workflow Engine** | Orchestrator — builds the applicable step sequence for a submission, tracks current step, decides who's notified next |
| **Approver Mapping Service** | Resolves Individual > Group > Default; handles delegate resolution |
| Notification Service | Fires on state-change events; decoupled from the services that cause them |
| Audit Service | Immutable log of every state transition, decision, and comment |
| Reimbursement Service | Picks up fully-approved reports; tracks Approved → Processed → Paid |

### Orchestration logic (per report, as a state machine)

At submission time, the engine evaluates the Conditional Approval Steps against *this specific expense* (amount, category) to build the actual step sequence — a ₹3,000 office-supply expense and a ₹60,000 travel expense produce genuinely different chains. This resolved sequence is attached to the report at submission time and does not change even if step configuration is edited later (mirrors "policy version at submission time").

At each step, the engine:
1. Asks Approver Mapping Service: who is the approver for this step? (Individual > Group > Default)
2. Checks: is that approver the submitter? If yes → cascades automatically: active delegate, else reporting manager, else Default Approver (§6) — resolved without any human intervention.
3. Checks: does the (possibly self-approval-redirected) approver have an active delegate (self-set or Admin-set)? If yes → routes to the delegate instead.
4. Notifies the resolved approver(s) via an event — not a direct call.
5. Waits for an action. On approve, advances to the next step or completes if last.

### Communication pattern

```
   Synchronous (caller waits)              Asynchronous (fire-and-forget, pub/sub)
   ───────────────────────────              ────────────────────────────────────────

   Expense Capture ──calls──► Policy Engine        Approval Workflow Engine
        │                     (needs result now)          │
        │                                                  │ publishes event
        ▼                                                  ▼
   Approver ──calls──► Approval Workflow Engine    ┌─────────────────┐
   (approve/reject,     (needs confirmation now)   │  Event: state     │
    needs ack now)                                  │  changed           │
                                                     └─────┬─────┬───────┘
                                                           │     │
                                                subscribes │     │ subscribes
                                                           ▼     ▼
                                                 Notification   Audit
                                                 Service         Service
                                                 (delivery failure  (always
                                                  never blocks       succeeds,
                                                  workflow progress)  immutable)
```

### Validations & permissions
- Policy Engine runs **before** the expense ever enters the Approval Workflow — violations are already known and attached.
- Permission checks on every action: an approve/reject/clarify request is only valid if the acting user is the currently-resolved approver (or their delegate) for that specific step — never just "any Manager."
- Self-approval check is baked into step resolution (step 2 above), not an afterthought.

### Notifications & reminders
- Event-driven, not polled — every state transition emits an event; Notification Service subscribes and decides what to send. If Notification Service is slow/down, the workflow itself keeps functioning.
- Reminders run as a separate scheduled process scanning for reports sitting too long in "Under Approval," emitting a reminder event. No auto-escalation — just a signal, visible to Admin.

### Scalability, maintainability, extensibility
- **Event-driven core** — Notification/Audit are just subscribers to state-change events. Adding a new subscriber later (e.g. Slack) means zero changes to the Approval Workflow Engine.
- **Idempotent actions** — approve/reject carries a unique reference so a double-click/retry can't double-process a decision.
- **Config versioning** — approval-step config and policy config both follow "snapshot at submission time," so Admin can edit config without affecting in-flight expenses.

---

## 12. Feature Trace — Start to Finish

| # | Step | Trigger |
|---|---|---|
| 1 | Employee submits (Frontend → Expense Capture Service → DB) | Automatic |
| 2 | Policy Engine evaluates, violations attached | Automatic |
| 3 | If any violation is Block-type → submission halted, employee must fix | Automatic gate, manual fix |
| 4 | Approval Workflow Engine resolves the step sequence (amount/category evaluated once, locked in) | Automatic |
| 5 | Approver Mapping Service resolves first approver (self-approval + delegate checks) | Automatic |
| 6 | Notification Service fires event to resolved approver | Automatic, async |
| 7 | Approver acts — approve / reject / ask clarification | **Manual — the one human step** |
| 8 | On approve: more steps? repeat from 5. No more steps? status → Approved | Automatic |
| 9 | On reject: status → Rejected, employee notified with reason, report unlocked | Automatic notify, manual fix |
| 10 | On resubmission: chain restarts fully from step 4 | Automatic |
| 11 | On full approval: Reimbursement Service picks up report | Automatic handoff, manual processing downstream |

### Edge cases & fallbacks
- Approver unavailable, no delegate, Admin hasn't noticed → sits in "Under Approval," reminders fire, visible in Admin's aging dashboard.
- Two parallel approvers act near-simultaneously → idempotent handling, second sees "already actioned by X."
- Employee edits expense during pending clarification → treated as their response, clarification resolved, returns to approver's queue.
- Admin deletes an approver's account with pending approvals → falls back to Default Approver.

---

## 13. System Design Principles

**Pattern:** modular, domain-organized backend — bounded contexts for Expense Capture, Policy, Approval Workflow, Notification, Audit, Reimbursement — whether deployed as separate services or well-separated modules in one deployable. Domain boundaries matter more than deployment topology at this stage; can start modular-monolith and split later without redesign, as long as module boundaries stay clean.

**Communication:**
- Synchronous, direct calls where the user is actively waiting on a response (submit → policy result; approve action → confirmation).
- Asynchronous events (pub/sub) for side effects the user isn't blocked on (notifications, audit logging, reminder scheduling). Keeps the Approval Workflow Engine fast and resilient — it doesn't care if Notification Service is healthy right now.

**Design patterns applied (conceptually):**
- **State Machine** — governs the report lifecycle; guarantees no invalid/ambiguous status.
- **Strategy Pattern** — each condition type (amount-based, category-based) is a self-contained strategy; adding a new condition type later (e.g. project-based) means one new strategy, not touching orchestration.
- **Chain of Responsibility** — naturally describes sequential approval steps.
- **Observer / Pub-Sub** — Notification and Audit observe workflow events without the engine needing awareness of them.
- **Repository Pattern** — abstracts data access so engine logic isn't coupled to storage details.

**Why this holds up:** the Approval Workflow Engine should only know about *workflow* — not how notifications get sent, how audit logs get stored, or how reimbursement gets processed. Each is a separate concern reached only through events/interfaces. This is what lets features get added later (new notification channel, new condition type, new reporting view) without touching or re-testing core orchestration — the kind of stability a financial system needs.

---

## 14. Consolidated Design Decisions

| Area | Decision |
|---|---|
| Approval levels | Fully configurable by Admin, no fixed number |
| Who can approve | Only Manager/Finance-role users |
| Approver mapping | **Individual > Group > Default**, per employee (mirrors Policy Assignment) — replaces the old cost-center + amount-range matrix entirely |
| Reporting-manager data | Available and reliable (synced from HR system of record) — usable as an Approver Mapping target and as the self-approval escalation step; not assumed absent |
| Routing | Conditional Approval Steps — amount and/or category trigger per step |
| Multiple approvers per step | Sequential, Parallel-Any, or Parallel-All — Admin's choice per step; steps can mix patterns |
| Duplicate-approver handling | Auto-skipped if the same person resolves twice in one chain |
| Rejection comment | Optional, gently prompted, skippable |
| Resubmission | Edit in place; chain restarts fully from the top |
| Clarification | Non-terminal "pending employee response" state supported |
| Delegation | Approver self-set, or Admin-assigned directly — both supported, same mechanism |
| Escalation (SLA breach) | Reminder notifications only; **no automatic reassignment** — a human (approver or Admin) must set a delegate to move it |
| Self-approval | **Fully automatic cascade** — delegate, else reporting manager, else Default Approver; never waits on Admin (deliberately more automated than SLA escalation, since this is a hard-rule violation, not a scheduling delay) |
| Approver editing the expense | Not allowed — approve, reject, or ask clarification only |
| Report approval granularity | Whole report only, all-or-nothing |
| Bulk approval | Supported for unflagged reports only |
| Silent approval | Allowed even on flagged (Warn-type) expenses |
| Audit trail visibility | Visible to employee, not just Admin/Finance |
| Notifications | Design deferred (channels/content to be scoped separately) |
| Build strategy | Full rewrite of the engine and its data model, not an extension of the old matrix-based engine — see §16 for what's deliberately carried forward as design ideas |

---

## 15. Open Questions

These are the remaining details worth explicit answers before/during implementation:

1. **Default Approver fallback — is there ever more than one?** Confirmed as the design intent (self-approval cascade, account-removal fallback), but the exact selection rule needs nailing down: one single global Default Approver, or a per-department/per-group default? Affects both the Approver Mapping precedence and the self-approval/removal fallback paths.
2. **Notifications:** channels, content, and timing are explicitly deferred — needs its own design pass before build.
3. **Reminder cadence:** "reminders fire on a schedule" — the actual interval/threshold (e.g. after 24h, 48h, escalating frequency) isn't yet defined.
4. **Bulk approval UI boundary:** confirm "no policy flags" is the sole exclusion criterion for bulk-eligibility, or whether other conditions (e.g. above a certain amount) should also exclude a report from bulk actions.
5. **Individual/Group assignment authoring:** when Admin sets an Individual or Group mapping to "reporting manager" rather than a fixed person, does that choice apply uniformly per employee/group, or can it vary per approval step within the same chain? Needs a concrete answer before the config UI can be designed.

---

## 16. Relationship to the Old Engine (EP06)

The old approval engine is not a stub — it's a complete, tested implementation (dedicated test coverage for every service). It's being replaced, primarily because its approver-mapping model (cost-center + amount-range matrix, no per-employee override) doesn't fit the target customer as well as Individual > Group > Default does, and because several features here (self-approval handling, clarification state, notification/audit wiring, bulk approval) don't exist in it at all and would be awkward to bolt on.

That said, four of its mechanics are proven and are deliberately carried forward as *design ideas* into this new engine — not as reused code, since the data model is changing, but as requirements a fresh implementation must not accidentally drop:

| Idea from EP06 | Why it's worth keeping |
|---|---|
| **Snapshot-at-submission** | The resolved chain is materialized and frozen at submission time, immune to later config edits. Already part of this design (§5) — flagged here so it survives the rewrite deliberately, not by accident. |
| **Optimistic locking / idempotent actions** | A version field (or equivalent) on each step/task record prevents two near-simultaneous actions (a race between approvers, or a double-click/retry) from double-processing the same decision. Easy to silently drop when writing fresh code — must be an explicit requirement. |
| **Duplicate-approver auto-skip** | If the same person resolves as approver at two different steps (common with small teams — e.g. the CFO is both a named Finance approver and the Default Approver), the later occurrence is auto-skipped rather than asking them to re-approve their own decision. Not discussed in the original conversation at all; a genuine quality-of-life addition worth keeping. |
| **Reporting-manager data as a real, reliable input** | The old engine's `MANAGER` approver type already resolves against real, continuously-synced reporting-manager data — proof this data source is dependable enough to build on, both for Approver Mapping (§4) and the self-approval cascade (§6). |

Two things the old engine did that this design explicitly does **not** carry forward, by choice, not oversight:
- **Automatic SLA-breach escalation** (skip-level reassignment with zero human involvement) — this design uses reminders-only instead (§6, §7), trading some automation for keeping a human in control of reassignment.
- **Cost-center + amount-range as the primary mapping key** — replaced by per-employee Individual > Group > Default, with amount/category demoted to step-level conditions rather than the mapping's primary axis.

---

## 17. Out of Scope (for this document)

- Migration/cutover plan from the old approval workflow (data migration for in-flight reports, dual-run period, rollback strategy) — to be scoped separately once implementation begins.
- Notification channel design (in-app/email content, timing, templates).
- Reimbursement Tracking internals (Approved → Processed → Paid) — referenced only as the downstream consumer.
