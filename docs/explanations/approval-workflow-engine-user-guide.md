# Approval Workflow Engine — User Guide

This guide explains how expense approvals work in plain terms — what happens when you submit a report, what an approver sees and can do, and how the rules that drive all of it are configured. No technical background is needed to follow this.

If you're looking for how the system is built internally, see the companion document, `approval-workflow-engine-technical.md`.

---

## The short version

When you submit an expense report, the system automatically works out **who needs to approve it, in what order**, based on rules your organization has configured (things like the amount, the category of expense, your department, or your cost center). You don't pick your own approvers — the system resolves them for you, every time, consistently.

Every approver reviews your report **line by line**, not as one big lump. Each line can be approved on its own, or sent back with a comment asking you to fix something — without the whole report having to restart. Once every line at a stage is approved, the report moves to the next stage automatically. Once every stage is done, the report is fully approved and moves on to reimbursement.

Three kinds of people interact with this system:

- **Employees** — submit reports and respond to feedback.
- **Approvers** — review and act on reports routed to them. (Almost anyone can end up in this role — it's not limited to people with a "Manager" title.)
- **Administrators** — configure the rules that decide who approves what.

---

## Part 1 — For Employees

### Submitting a report

Your report starts as a **Draft**. While it's a draft, you can freely add, edit, or delete line items, and edit the report's own details (title, business purpose, cost center, currency). Once you're ready, click **Submit for Approval** on the report's detail page.

At the moment you submit, the system:
1. Checks the report has at least one line item and a cost center.
2. Checks for policy issues on your line items (see "About policy warnings" below).
3. Works out the full chain of approval stages your report needs to go through, based on your organization's configured rules — and locks that chain in. **Editing the rules later never changes a report that's already been submitted.**
4. Sends it to the first stage's approver(s).

You'll see the report's status change to **Pending Approval**, along with a note showing which stage it's currently waiting on (for example, "Pending Manager Review") if your organization has named that stage — otherwise it just shows "Level 1," "Level 2," and so on.

### What each status means

| Status | What it means |
|---|---|
| **Draft** | Not submitted yet. Fully editable, or deletable. |
| **Pending Approval** | Submitted, currently waiting on an approver at some stage. Shows which stage. |
| **Awaiting Correction** | An approver flagged at least one line item and asked you to fix it — see below. Your report is still editable while in this state (just not deletable). |
| **Approved** | Every required stage signed off. Moves on to reimbursement handling automatically. |
| **Rejected** | An approver made a final decision to reject the whole report. This is different from a correction request — see below. There is no way to resubmit a rejected report; you'd need to create a new one. |
| **Cancelled** | You abandoned the report yourself. Final — cannot be resubmitted. |

### If an approver asks for a correction

An approver reviews your report **one expense line at a time**. If everything on a line looks right, they approve it. If something's wrong with a *specific* line — a missing receipt, wrong amount, unclear description — they flag just that line as **Needs Correction** and leave a comment explaining what to fix. They cannot flag a line without leaving a comment; you'll always know exactly what needs attention.

When this happens:
- Your report's status changes to **Awaiting Correction**.
- You'll see a banner listing exactly which line(s) were flagged and the approver's comment for each.
- Any *other* lines that were already approved stay approved — you don't lose that progress.
- Fix the flagged line(s), then click **Resubmit**.

What happens next depends on whether your fix changed anything that affects routing (like the amount crossing a threshold, or a different category):
- **Usually**, nothing about the routing changes — your report picks up right where it left off, with the same approver who flagged it.
- **Occasionally**, if your fix changes which approval rule applies to your report, the whole chain is recalculated from scratch and you start over at the first stage under the new rule. You won't be asked to redo anything manually — this happens automatically; you'll just see the status pill reflect the new starting stage.

### If your report is rejected

A **Reject** is different from a correction request. It's a final decision by an approver — for example, a duplicate submission, a fraudulent claim, or simply the wrong report entirely. When this happens, you'll see the status **Rejected** along with the approver's comment explaining why. There is nothing to fix and resubmit — a rejected report is done. If the expense is still legitimate, you'll need to create a fresh report for it.

### Recall and Cancel

While your report is pending, you have two ways to pull it back yourself, without waiting on an approver:

- **Recall to Draft** — pulls the report back to Draft so you can keep editing it before submitting again later.
- **Cancel** — abandons the report entirely. Final; cannot be undone.

**Both of these stop being available the moment any stage of your report has already been fully approved.** Once at least one stage has signed off, you can no longer recall or cancel — the buttons simply won't appear. This protects the approvals that already happened from being silently discarded.

### About policy warnings

Separately from approval, your organization may have spending-policy rules (receipt requirements, amount limits, backdating limits, and so on) that flag warnings on individual line items — for example, "this amount exceeds the configured limit by 40%." These currently act as **warnings only** — they don't block you from submitting, and an approver will see them alongside your line items when reviewing. (Whether a future policy rule can outright block submission is a separate setting your organization controls; check with your Admin if you're unsure what's currently enforced.)

### Your expense list

Your **My Expenses** list shows every report you own, with the same status labels described above, so you can track everything at a glance without opening each report.

---

## Part 2 — For Approvers

### Who becomes an approver

Anyone can end up needing to approve an expense — there's no special role or title required. The system resolves approvers automatically based on rules like "the submitter's manager," "the submitter's department owner," "the submitter's cost center owner," or a specific named person. If you're ever routed a report to review, it's because one of those rules resolved to you — not because of a role you were assigned.

### Your Pending Approvals queue

This is where every report currently waiting on your action shows up — reached from the sidebar under **Approvals → Pending**. Each row shows the report number, the employee, the total amount, and which stage it's at. Click a row to expand it and see every line item still waiting on your decision, with full context for each one: merchant, date, description, amount, category, and any policy warnings already flagged on it — no need to open a separate screen or check receipts elsewhere.

For each line item, you have two choices:
- **Approve** — this specific line is fine.
- **Needs Correction** — something's wrong with this specific line. You must leave a comment; the employee will see it verbatim.

You can also **Reject** the whole report from this screen — a distinct, more serious action from flagging a single line, reserved for cases where the entire report shouldn't proceed at all (duplicate, fraud, wrong report). Rejecting requires a comment too, and it's final — there's no walking it back once done.

### Bulk Approve

If a report has **no open policy warnings anywhere** on it, you'll see a **Bulk Approve** button — one click approves every pending line at once, instead of going line by line. If the report has any outstanding policy warning, this option won't appear; you'll need to review it line by line so nothing gets waved through unseen.

### What happens after you act

- Approving every remaining line at your stage completes that stage and moves the report to the next one — you'll see it drop out of your queue.
- If your stage requires more than one approver to act in sequence, approving your part moves it on to the next approver in that same stage; the report doesn't leave the stage until everyone required has acted.
- If your stage lets *any one* of several people act, whoever acts first completes the stage for everyone else at that stage.
- Flagging a line as Needs Correction sends the whole report back to the employee immediately — the stage stops there until they fix it and resubmit.

Your queue updates live — if someone else acts on a report (a co-approver, or a delegate covering for you), you'll see it change without needing to refresh the page.

### Approved and Rejected history

Two more tabs under **Approvals** — **Approved** and **Rejected** — list every report you've already decided on, so you can look back at what you've handled.

### Setting a delegate

If you're going to be unavailable, you can set someone to stand in for you without any admin involvement. At the top of your Pending Approvals page, the **My Delegate** card lets you name a delegate and a date range. While that window is active, your delegate can act on anything routed to you — the system checks this automatically every time an action is attempted, so nothing needs to be manually reassigned. Outside that window, authority reverts to you automatically.

If you set overlapping delegate periods by mistake, the most recently created one takes effect — worth double-checking your dates if you're changing a delegate you'd already set up.

**Important:** the system will never let you approve your own report, even indirectly. If you happen to resolve as an approver on your own expense (for example, you're also your own cost center's owner), the system automatically substitutes your active delegate, or your manager, or your organization's designated fallback approver — whichever applies first — before anyone is even notified. You'll never see your own report land in your own queue.

---

## Part 3 — For Administrators

Administrators configure the rules that decide routing — they don't get involved in day-to-day approval decisions themselves (unless they also happen to be a resolved approver on a specific report, in which case they act as any approver would). Everything below lives under the sidebar's **Approval Rules** section.

### Approval Flows

A **Flow** is one named routing rule: a set of conditions (its **criteria**) plus an ordered list of **levels** (approval stages). The **Flows** screen lists every flow you've configured, in priority order — lower priority number means it's checked first. **The first flow whose conditions match a report is the only one that runs for it** — flows are not combined or stacked.

**Creating or editing a flow** takes you to its own dedicated screen with three parts:

1. **Basic details** — name, priority, and active/inactive status.
2. **Match Criteria** — the conditions that decide whether this flow applies. You build conditions in groups: each group is a set of conditions that must **all** be true (AND), and a report matches the flow if **any** group is satisfied (OR). For example: "(Amount is greater than 10,000 AND Category is Travel) OR (Department equals Sales)." You can drag conditions to reorder them within a group, and drag whole groups to reorder them.
   - Conditions can check: **Amount** (compared with greater-than/less-than/equals), **Category**, **Department**, or **Cost Center** (the latter three can only be checked for equals/not-equals, not greater/less-than).
3. **Approval Levels** — the ordered stages a matching report goes through. For each level you set:
   - An optional **name** (shown to employees and approvers as the stage label — if left blank, it just shows as "Level 1," "Level 2," etc.)
   - A **quorum** — how the level's approvers must act:
     - **Sequential** — one after another, in the order you set.
     - **Any Of** — whoever acts first completes the level for everyone.
     - **All Of** — intended for requiring every approver at that level to act (see note below).
   - One or more **approvers**, each sourced as: a **Named User** (you pick a specific person), the submitter's **Reporting Manager**, their **Department Owner**, or their **Cost Center Owner**. You can drag to reorder both levels and approvers within a level.

   > **Note on "All Of":** at the time of writing, "All Of" behaves the same as "Any Of" — the level completes as soon as the first listed approver finishes, rather than waiting for every one of them. If your rule genuinely depends on requiring multiple independent sign-offs at one level, check with your engineering team before relying on this — it's a known, tracked gap, not intended final behavior.

Once a report has been submitted under a given flow, **changing that flow's rules afterward has no effect on that report** — its approval chain was already locked in at submission time. This means you can safely adjust rules going forward without worrying about disrupting anything already in progress.

### The Catch-All Flow

Every report must resolve to *some* flow — there's no "nothing matched" dead end. If a report doesn't match any of your named flows, it automatically falls through to the **Catch-All Flow**, a single, simpler flow with no conditions and no priority of its own — it just has levels, configured on its own screen. You must set this up (at least one level) before anyone in your organization can submit a report at all — until you do, submission will fail with an error.

### Department Approvers

If any of your flows use the **Department Owner** approver source, you need a mapping here: which employee approves for which department. Each department can have exactly one approver mapped. If a flow routes to a department with no mapping configured here, submissions that reach that level will fail — set this up before relying on it.

### Delegations

This screen shows **every** delegation currently set up across your organization — who's covering for whom, and for what date range — regardless of who set it up. You can create, edit, or remove any delegation from here, which is useful for covering for someone who's unexpectedly unavailable and hasn't set their own delegate. This is the same underlying feature approvers use themselves from their own queue page (Part 2) — you're just seeing the full picture across everyone at once.

---

## Common scenarios, start to finish

**"I submitted a report — what happens now?"**
The system immediately works out your full approval chain and notifies the first approver. Your report shows "Pending Approval" with the current stage name. You don't need to do anything else unless someone asks you to fix something.

**"An approver flagged one of my expense lines."**
Check the banner on your report for exactly which line and why. Fix it, then click Resubmit. Everything else on the report that was already approved stays approved.

**"I need someone to cover my approvals while I'm out."**
Go to your Pending Approvals page and set a delegate with a start/end date in the My Delegate card. They'll automatically be able to act on anything routed to you during that window — nothing else needs to change.

**"A report I need to approve also happens to be mine."**
This can't actually happen — the system detects it automatically and reroutes it to your delegate, your manager, or your organization's fallback approver before you'd ever see it.

**"I approved a report, but it's still showing as pending."**
If the current stage has more than one approver required (an "All Of" or multi-person "Sequential" stage), your action alone may not be enough to complete it — another approver at the same stage may still need to act.

**"My report was rejected. Can I fix it and resubmit?"**
No — rejection is final, unlike a correction request. You'll need to create a new report if the expense is still valid.

**"I want to pull back a report I already submitted."**
Use Recall (if you want to keep editing and resubmit later) or Cancel (if you're abandoning it). Neither is available anymore once at least one stage has already fully signed off.

---

## Glossary

| Term | Plain-language meaning |
|---|---|
| **Flow** | One named routing rule — decides which reports it applies to and what stages they go through. |
| **Level / Stage** | One step in the approval chain (e.g. "Manager Review"). |
| **Quorum** | How a level's approvers must act: one-by-one in order, first-one-wins, or (intended, not yet fully working) everyone must act. |
| **Approver source** | How the system decides *who* approves at a level — a named person, your manager, your department's approver, or your cost center's owner. |
| **Catch-All Flow** | The fallback flow every report lands in if no named flow's conditions match it. |
| **Needs Correction** | A specific line item was sent back to you with a required comment — fix it and resubmit, no restart needed. |
| **Reject** | A final, whole-report decision — no resubmission possible. |
| **Recall** | Pull your own pending report back to Draft. |
| **Cancel** | Abandon your own report for good. |
| **Delegate** | Someone standing in for an approver during a set date range. |
| **Bulk Approve** | Approve every pending line on a report in one click — only available when nothing on it has an open policy warning. |
