-- DEV ONLY. Run after after-trip-cash-advances.sql. For three of those advances (F Bengaluru, G Chennai, H Mumbai)
-- adds an after-trip expense report that stops at the Line Items step:
--   * report_status DRAFT, linked to the advance, report details filled in (title, purpose, cost center, trip dates);
--   * two line items each, every report totalling under 500 INR, no receipts (no active category requires one);
--   * not submitted, so no expense approval has started (no approval_task rows, submitted_at NULL).
-- The advances stay IN_PROGRESS; advances I and J are left without a report.
-- Rollback: after-trip-draft-reports-rollback.sql.

SET @emp      = '5100014';
SET @currency = UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'); -- INR
SET @cc       = UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'); -- Eng
SET @meals    = UNHEX('48ffa37cca0b4ffeb53d973cea07e46f'); -- Meals
SET @local    = UNHEX('f2147cdf4fb749f6b6f80abf40d91c5b'); -- Local Transportation

START TRANSACTION;

INSERT INTO expense_report (report_id, version, report_number, employee_id, title, business_purpose, report_status,
    fiscal_year, cost_center_id, currency_id, cash_advance_id, trip_start_date, trip_end_date,
    total_amount, reimbursable_amount, invoice_handoff_status, payment_routing_status,
    company_paid, no_payment_due, expense_deadline_reopened, created_at, updated_at)
VALUES
(UNHEX('90d55959455941dda87ce4c97d0dbe40'), 0, 'EXP-2026-2027-106A5121', @emp,
    '[SEED] Bengaluru client visit expenses', 'After-trip expenses for the Bengaluru client visit (SEED-AFTERTRIP-REPORT F)', 'DRAFT',
    '2026-2027', @cc, @currency, UNHEX('fcc23f1bbd9c4af6927b63395cfcc008'), '2026-10-26', '2026-10-30',
    430.0000, 430.0000, 'NOT_APPLICABLE', 'NONE', b'0', b'0', b'0', '2026-11-19 10:00:00', '2026-11-19 10:15:00'),
(UNHEX('daccd2089a1b47469fcbeb4ea8f2d817'), 0, 'EXP-2026-2027-6778AC5B', @emp,
    '[SEED] Chennai workshop expenses', 'After-trip expenses for the Chennai training workshop (SEED-AFTERTRIP-REPORT G)', 'DRAFT',
    '2026-2027', @cc, @currency, UNHEX('af37e9a9ddd54d2d998b8e32036e968a'), '2026-10-29', '2026-11-02',
    370.0000, 370.0000, 'NOT_APPLICABLE', 'NONE', b'0', b'0', b'0', '2026-11-19 11:00:00', '2026-11-19 11:15:00'),
(UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf'), 0, 'EXP-2026-2027-BCDC82E0', @emp,
    '[SEED] Mumbai audit expenses', 'After-trip expenses for the Mumbai audit trip (SEED-AFTERTRIP-REPORT H)', 'DRAFT',
    '2026-2027', @cc, @currency, UNHEX('36b2d34e45264b4782879d1aec104054'), '2026-11-03', '2026-11-07',
    474.0000, 474.0000, 'NOT_APPLICABLE', 'NONE', b'0', b'0', b'0', '2026-11-20 09:00:00', '2026-11-20 09:15:00');

-- Line items: amount = net (tax-inclusive, no tax), INR so base = amount and rate 1.
INSERT INTO expense_line_item (line_item_id, report_id, category_id, cost_center_id, currency_id, expense_date,
    merchant_name, description, amount, base_amount, net_amount, base_net_amount, exchange_rate,
    tax_amount, base_tax_amount, recoverable_tax_amount, base_recoverable_tax_amount, itc_recoverable_percent,
    tax_treatment, tax_source, tax_validation_status, client_billable, employee_payment_method,
    line_status, created_by, created_at, updated_at)
SELECT UNHEX(l.id), UNHEX(l.report), l.cat, @cc, @currency, l.d, l.merchant, l.descr,
       l.amt, l.amt, l.amt, l.amt, 1.000000, 0, 0, 0, 0, 0.00,
       'INCLUSIVE', 'NONE', 'NOT_APPLICABLE', b'0', 'CASH', 'ACTIVE', 'MANUAL', l.ts, l.ts
FROM (
              SELECT '057645760b9848f4bc7d76beaf11deae' id, '90d55959455941dda87ce4c97d0dbe40' report, @local cat, DATE '2026-10-26' d, 'Uber' merchant, 'Airport to client office' descr, 180.0000 amt, TIMESTAMP '2026-11-19 10:05:00' ts
    UNION ALL SELECT '6133f5966bb44e48bb3d57f5d3c38699', '90d55959455941dda87ce4c97d0dbe40', @meals, DATE '2026-10-27', 'Udupi Grand', 'Working lunch', 250.0000, TIMESTAMP '2026-11-19 10:10:00'
    UNION ALL SELECT '908f8baf3e194013ad0ce00a8570f90a', 'daccd2089a1b47469fcbeb4ea8f2d817', @meals, DATE '2026-10-30', 'Saravana Bhavan', 'Dinner during workshop', 220.0000, TIMESTAMP '2026-11-19 11:05:00'
    UNION ALL SELECT '95806c80386e40f680cce2255ff3d12d', 'daccd2089a1b47469fcbeb4ea8f2d817', @local, DATE '2026-10-31', 'Ola', 'Hotel to workshop venue', 150.0000, TIMESTAMP '2026-11-19 11:10:00'
    UNION ALL SELECT 'ab1dbcb09543494da56e0fa89cc008af', '36c1caa7cfaa4645b06d3ba536ec2adf', @local, DATE '2026-11-04', 'Mumbai Metro / Auto', 'Local travel to client site', 199.0000, TIMESTAMP '2026-11-20 09:05:00'
    UNION ALL SELECT '21eb9cd4a0774b88be282a0edcd967be', '36c1caa7cfaa4645b06d3ba536ec2adf', @meals, DATE '2026-11-05', 'Cafe Madras', 'Lunch with audit team', 275.0000, TIMESTAMP '2026-11-20 09:10:00'
) l;

COMMIT;

-- Check:
-- SELECT r.report_number, r.title, r.report_status, r.total_amount, COUNT(li.line_item_id) line_count, MAX(li.amount) max_line
-- FROM expense_report r LEFT JOIN expense_line_item li ON li.report_id = r.report_id
-- WHERE r.business_purpose LIKE '%SEED-AFTERTRIP-REPORT%' GROUP BY r.report_id;
