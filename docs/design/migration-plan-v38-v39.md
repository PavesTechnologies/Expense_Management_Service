# Migration plan: V38 and V39 (prepared, not executed)

Status: **prepared for approval. Nothing has been run against any database.** This plan follows the decision record:
reconcile the V30/V31 history, inspect the dev schema, review V38, prepare a backup and migration plan, and request
explicit confirmation before executing against the identified dev database.

Dev database identified from the backend `.env` (host and schema only; credentials are not recorded here):
`mysql-2652f645-expensemanagement.d.aivencloud.com`, schema `expense_management`, MySQL 8.4.8.

## 1. What the read-only audit found (2026-10-23)

The audit used a read-only session and `information_schema` / `flyway_schema_history` SELECTs only.

**Flyway history on dev** runs V1 to V31, all marked successful, last installed 2026-10-16.

**Mismatches between dev history and the repo:**

| Version | Dev history (script, description) | Repo file |
|---|---|---|
| V29 | `V29__receipt_ocr_category_hint.sql`, "add cash advance title" | `V29__receipt_ocr_category_hint.sql` (same name, different description) |
| V30 | `V30__seed_cash_advance_budget_limit.sql`, "seed cash advance budget limit" | `V30__cash_advance_ep04_enhancements.sql` (**name not in dev history**) |
| V31 | `V31__system_configuration_version.sql`, "system configuration version" | `V31__cash_advance_fee_and_company_paid.sql` (**name not in dev history**) |

If Flyway validation ran against this database, it would report applied migrations that the repo does not contain.
Flyway is disabled in `application.properties`, so this is latent, but the history must be reconciled before anything
else is migrated through Flyway.

**Versions not in dev history but whose effects are already present on dev:**
- V32 to V37 are not in the history. The columns from `scripts/V35` and `scripts/V36` (`disbursed_at`,
  `disbursement_*`, `expense_deadline_*`, `deadline_reminder_sent_at`, `trip_start_date`, `trip_end_date`) exist on dev.
  They came from Hibernate `ddl-auto=update` or a manual change, not from a recorded migration.
- The V38 repayment columns (`payment_mode`, `received_by_name`, `transfer_date`, `transfer_details`,
  `payee_upi_id`, `proof_reference`, `confirmation_note`) already exist on dev, with types that differ slightly from the
  entity (`tinytext`, `varchar(512)`). Their source is not recorded. V38 is written to skip existing columns.
- `cash_advance_adjustment.idempotency_key` already exists on dev, so V39 skips the column and adds only the index.

**Repo conflicts:**
- `src/main/resources/db/migration`: `V6` appears twice (`V6__add_ocr_pipeline_fields_to_receipt_ocr.sql`,
  `V6__create_search_index_table.sql`) and `V34` appears twice (`V34__cash_advance_budget_fiscal_year.sql`,
  `V34__tax_line_supplier_gstin.sql`). Flyway refuses duplicate versions.
- `src/main/resources/db/migration` V35, V36, V37 (expense allocation, payment confirmation, invoice sync) and
  `scripts/V35`, `V36`, `V37` (cash-advance disbursement, trip dates, disbursement proof) use the same numbers for
  unrelated changes. Both sets are needed on dev, so they cannot both keep their numbers.
- `scripts/V37` depends on columns from `scripts/V35`.

## 1a. Read-only comparison of V30 and V31 effects (2026-10-23)

Run with a read-only session; only `SELECT` statements were executed.

| Repo script | Changes in repo | Present on dev? |
|---|---|---|
| V30 `cash_advance_ep04_enhancements` | `cash_advance`: `version`, `approved_amount`, `settlement_status`, `settled_at`, `settled_by`; `cash_advance_adjustment`: `version`, `payment_method`, `transaction_reference`, `payment_date`, `disbursement_status`, `idempotency_key` (UNIQUE); `cash_advance_repayment.version`; unique `uk_advance_report` | **Yes, all present.** Dev also has the unique key on `idempotency_key` (`UK6hfwdko3q5nclp6ec2dffrtm5`) and `uk_advance_report`. |
| V31 `cash_advance_fee_and_company_paid` | `cost_center_budget.cash_advance_fee_allocated`; `expense_report.company_paid` | **Yes, both present.** |

**Dev history names that do not match the repo:**
- `V30__seed_cash_advance_budget_limit.sql` ("seed cash advance budget limit"). No repo script contains a
  cash-advance budget-limit seed, and V30 contains no INSERT. The dev row count for budget seeds has not been compared
  against any repo data, so whether dev's seed matches anything in the repo is unknown.
- `V31__system_configuration_version.sql` ("system configuration version"). No repo script changes `system_configuration`.
  Its `version` column (present on dev) comes from the entity, not from a migration. `system_configuration` has 6 rows on dev.

**Conclusion.** The effects of the repo's V30 and V31 are present on dev, so the schema is consistent with the repo for
those two scripts. The history rows are not: the names for V30 and V31 on dev describe scripts that are not in the repo.
Reconciliation must decide whether those history rows are corrected or left and documented (section 2).

**V39 correction.** The repo's V30 already makes `idempotency_key` UNIQUE, and dev has that unique key. V39 therefore
creates the index only when no unique index exists on that column at all, instead of always adding one.

## 2. Reconciliation of the history (decision required)

Proposed steps, in order. None of them changes the database until approved.

1. **Compare, read-only.** For V30 and V31, check whether the dev effects match the repo scripts (for example, the
   budget-limit seed and the system configuration version row). Owner: backend lead with Finance for the seed data.
2. **Decide per version:**
   - If the dev effects match the repo file, correct the history row to the repo's script name and checksum.
     This needs a Flyway repair, which must be approved.
   - If they do not match, do not edit history. Add a new versioned script that brings the repo into line with dev,
     and document the difference.
3. **Renumber the colliding scripts.** Keep the `src` V35–V37 files as applied, and give the `scripts/` files new
   numbers after the highest applied version (for example V40 to V42, to be chosen during review). Fix `scripts/V37`'s
   dependency on `scripts/V35` when renumbering.
4. **Resolve the duplicates.** Give each second `V6` and `V34` a new number. Confirm first which one dev applied
   (the history shows the OCR-pipeline V6 and the fiscal-year V34 as the applied versions, so the other files need
   new numbers).

## 3. Review of V38 and V39

- **V38** (`scripts/V38_cash_advance_repayment_payment_fields.sql`): seven columns on `cash_advance_repayment`, each
  guarded by `information_schema`. Review finding: the columns already exist on dev, so the run will be a no-op on dev
  and only adds the columns on databases that lack them. Confirm the `tinytext` types on dev are acceptable, or
  correct the entity mapping to match.
- **V39** (`scripts/V39_expense_report_deadline_override_and_adjustment_idempotency.sql`): two columns on
  `expense_report` (`expense_deadline_extended_until DATE NULL`, `expense_deadline_reopened BIT(1) NOT NULL DEFAULT 0`)
  and a unique index on `cash_advance_adjustment.idempotency_key`. Pre-check: the script's header query must return
  zero duplicate keys, or the index creation fails. NULLs are allowed in multiple rows.

## 4. Execution plan (after approval only)

1. **Backup.** Full logical dump of the dev schema (`mysqldump --single-transaction`), plus row counts of
   `cash_advance`, `cash_advance_repayment`, `cash_advance_adjustment`, `expense_report` and
   `flyway_schema_history`. Store the dump outside the repo. Owner approves the backup location.
2. **Pre-checks (read-only).** The duplicate-key query from V39's header. The V38 existence check.
3. **Rehearsal.** Restore the backup into a separate scratch database and run V38 then V39 there. Run the verification
   SELECT at the end of each script and compare to the expected columns.
4. **Maintenance window.** Run V38, then V39, on dev only, after the owner's explicit confirmation for that named
   database. Record the start and end time and the outcome of each verification query.
5. **Verify.** Re-run the verification queries. Start the backend against dev and check that
   `expense_report` and `cash_advance_repayment` queries work and that the adjustment idempotency index exists.
6. **Rollback.** V38 and V39 are additive. If a problem appears, drop only the columns and index that this plan added,
   and only after confirming no rows depend on them. Otherwise restore from the backup. Never drop columns that
   existed before this plan.

## 5. Approvals required before any step runs

- Backend lead: the history reconciliation decision (section 2) and the renumbering.
- Owner of the dev database: the backup location, and explicit confirmation of the target database and time window.
- Finance: the budget-limit seed comparison for V30 (section 2, step 1).

No migration has been run. The V38 and V39 scripts are not on any execution path.
