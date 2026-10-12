-- Backfill: charge cash advances approved BEFORE budget allocation existed, in base currency.
--
-- Those approvals never deducted the principal from cost_center_budget.available_budget, so the budget
-- overstates what is left to spend. This script applies the missing deduction once per cost center.
--
-- Currency: the budget is in base currency. Each principal is converted with the latest stored rate
-- on or before today (the same rate table the application uses), rounded to the base currency's
-- decimal places. Advances with no rate to base currency are listed and SKIPPED, never charged at face value.
--
-- Idempotency: every charged advance gets a cash_advance_fee row (amount 0, rate 0). The application treats
-- an existing fee row as "already allocated", and this script only picks up advances without one.
--
-- Safety:
--   * One transaction. Nothing is final until you run COMMIT.
--   * Take a database backup first.
--   * Advances whose cost center has no budget row for the fiscal year are listed and skipped.
--
-- Assumptions to check before running:
--   * @base_code must equal the backend's exchange.rate.base-currency (EXCHANGE_RATE_BASE_CURRENCY).
--   * UUID columns are BINARY(16) (Hibernate 6's default on MySQL). Check with
--     SHOW CREATE TABLE cash_advance_fee;  If fee_id is CHAR(36), change the UNHEX(...) in the INSERT to UUID().
--   * The conversion uses today's rate. The approval date is not stored on cash_advance, so the rate used
--     can differ slightly from the rate that was in effect at approval.
--
-- Usage:
--   1. mysql -u <user> -p <database> < scripts/cash_advance_allocation_backfill.sql
--   2. Read every preview and verification result.
--   3. Type COMMIT;  to apply, or ROLLBACK;  to discard.

START TRANSACTION;

-- Lock the budget rows for the fiscal year for the whole transaction, so no approval or payment can change them mid-run.
SELECT budget_id FROM cost_center_budget WHERE fiscal_year = '2026-2027' FOR UPDATE;

SET @fy_label := '2026-2027';
SET @fy_start := '2026-04-01';
SET @fy_end   := '2027-04-01';
SET @base_code := 'INR';

-- Base currency id and decimal places. Both must be non-NULL, or nothing will convert.
SELECT currency_id, decimal_places INTO @base_id, @base_dp
FROM currency
WHERE UPPER(currency_code) = UPPER(@base_code);

SELECT @base_id AS base_currency_id, @base_dp AS base_decimal_places,
       IF(@base_id IS NULL, 'ABORT: base currency not found; do not COMMIT', 'ok') AS base_check;

-- Advances to charge, with the principal converted to base currency.
CREATE TEMPORARY TABLE ca_backfill AS
SELECT
    a.advance_id,
    a.cost_center_id,
    CASE
        WHEN a.currency_id = @base_id THEN ROUND(a.amount, @base_dp)
        ELSE ROUND(a.amount * (
            SELECT r.exchange_rate
            FROM exchange_rate r
            WHERE r.from_currency_id = a.currency_id
              AND r.to_currency_id = @base_id
              AND r.effective_date <= CURDATE()
            ORDER BY r.effective_date DESC, r.fetched_at DESC
            LIMIT 1
        ), @base_dp)
    END AS base_principal
FROM cash_advance a
JOIN cost_center_budget b
  ON b.cost_center_id = a.cost_center_id
 AND b.fiscal_year = @fy_label
WHERE a.status IN ('APPROVED', 'DISBURSED', 'IN_PROGRESS', 'RECONCILIATION_PENDING',
                   'SUBMITTED_FOR_REVIEW', 'UNDER_REVIEW', 'SETTLEMENT_PENDING',
                   'CLOSED', 'SETTLED')
  AND a.created_at >= @fy_start
  AND a.created_at <  @fy_end
  AND NOT EXISTS (SELECT 1 FROM cash_advance_fee f WHERE f.advance_id = a.advance_id)
  AND (
        a.currency_id = @base_id
     OR EXISTS (SELECT 1 FROM exchange_rate r2
                WHERE r2.from_currency_id = a.currency_id
                  AND r2.to_currency_id = @base_id
                  AND r2.effective_date <= CURDATE())
  );

-- PREVIEW 1: what will be charged, per cost center, in base currency
SELECT cost_center_id, COUNT(*) AS advances, SUM(base_principal) AS base_principal_to_deduct
FROM ca_backfill
GROUP BY cost_center_id;

-- PREVIEW 1b: any budget that would go below zero. If this returns rows, run ROLLBACK; and do not commit.
SELECT b.cost_center_id, b.available_budget,
       COALESCE(t.total, 0) AS principal_to_deduct,
       b.available_budget - COALESCE(t.total, 0) AS available_after
FROM cost_center_budget b
LEFT JOIN (SELECT cost_center_id, SUM(base_principal) AS total FROM ca_backfill GROUP BY cost_center_id) t
  ON t.cost_center_id = b.cost_center_id
WHERE b.fiscal_year = @fy_label
  AND b.available_budget - COALESCE(t.total, 0) < 0;

-- PREVIEW 2: approved advances in this fiscal year that are NOT charged, and why they are skipped
SELECT
    a.advance_id, a.cost_center_id, a.amount, a.currency_id, a.status,
    CASE
        WHEN a.advance_id IN (SELECT advance_id FROM cash_advance_fee) THEN 'already has a fee row'
        WHEN NOT EXISTS (SELECT 1 FROM cost_center_budget b
                         WHERE b.cost_center_id = a.cost_center_id AND b.fiscal_year = @fy_label)
            THEN 'no budget row for this cost center and fiscal year'
        ELSE 'no exchange rate to base currency'
    END AS skipped_because
FROM cash_advance a
WHERE a.status IN ('APPROVED', 'DISBURSED', 'IN_PROGRESS', 'RECONCILIATION_PENDING',
                   'SUBMITTED_FOR_REVIEW', 'UNDER_REVIEW', 'SETTLEMENT_PENDING',
                   'CLOSED', 'SETTLED')
  AND a.created_at >= @fy_start
  AND a.created_at <  @fy_end
  AND a.advance_id NOT IN (SELECT advance_id FROM ca_backfill);

-- 1. Deduct the base principal from available budget and record it as allocated
UPDATE cost_center_budget b
JOIN (
    SELECT cost_center_id, SUM(base_principal) AS total
    FROM ca_backfill
    GROUP BY cost_center_id
) t ON t.cost_center_id = b.cost_center_id
SET b.available_budget = b.available_budget - t.total,
    b.cash_advance_allocated = COALESCE(b.cash_advance_allocated, 0) + t.total
WHERE b.fiscal_year = @fy_label;

-- 2. Mark each charged advance so it cannot be charged again
INSERT INTO cash_advance_fee (fee_id, advance_id, cost_center_id, fee_rate, amount, expense_report_id, created_at)
SELECT UNHEX(REPLACE(UUID(), '-', '')), t.advance_id, t.cost_center_id, 0, 0, NULL, NOW()
FROM ca_backfill t;

-- VERIFY 1: available budget should have dropped by exactly the base principal in PREVIEW 1
SELECT fiscal_year, budget_amount, available_budget, cash_advance_allocated, cash_advance_fee_allocated
FROM cost_center_budget
WHERE fiscal_year = @fy_label;

-- VERIFY 2: marker rows written (should equal the advance count in PREVIEW 1)
SELECT COUNT(*) AS fee_marker_rows_written FROM ca_backfill;

-- Nothing is final yet. Run COMMIT; to apply, or ROLLBACK; to discard.
