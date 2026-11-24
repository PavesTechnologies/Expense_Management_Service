-- DEV ONLY. Run after after-trip-draft-reports.sql. Adds one 5000 INR hotel-stay line item to the draft report
-- '[SEED] Mumbai audit expenses' (EXP-2026-2027-BCDC82E0), linked to advance H '[SEED] Mumbai audit trip' (8000 INR).
--   Before: 2 line items, 474 INR consumed, 7526 balance.
--   After:  3 line items, 5474 INR consumed, 2526 balance (still within the advance).
-- The report stays DRAFT (not submitted). The cost center budget is not charged.
-- Rollback: mumbai-report-hotel-line-item-rollback.sql.

SET @emp      = '5100014';
SET @report   = UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf'); -- EXP-2026-2027-BCDC82E0
SET @line     = UNHEX('c7a1e3f05b2d4e8a9f61d2b47c03a915');
SET @currency = UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'); -- INR
SET @cc       = UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'); -- Eng

-- Hotel / lodging category, looked up by name. Check it first:
--   SELECT HEX(category_id), category_code, category_name, receipt_required, max_limit, status
--   FROM expense_category WHERE category_name REGEXP 'hotel|lodg|accommod|stay';
-- If it is receipt_required or has max_limit < 5000, the app will flag the line until a receipt is attached.
SET @hotel = (SELECT category_id FROM expense_category
              WHERE category_name REGEXP 'hotel|lodg|accommod|stay'
                AND (status IS NULL OR status = 'ACTIVE')
              ORDER BY category_name LIMIT 1);

START TRANSACTION;

INSERT INTO expense_line_item (line_item_id, report_id, category_id, cost_center_id, currency_id, expense_date,
    merchant_name, description, amount, base_amount, net_amount, base_net_amount, exchange_rate,
    tax_amount, base_tax_amount, recoverable_tax_amount, base_recoverable_tax_amount, itc_recoverable_percent,
    tax_treatment, tax_source, tax_validation_status, client_billable, employee_payment_method,
    line_status, created_by, created_at, updated_at)
VALUES (@line, @report, @hotel, @cc, @currency, DATE '2026-11-03',
    'Hotel Sahara Star', 'Hotel stay for audit trip (3-7 Nov)',
    5000.0000, 5000.0000, 5000.0000, 5000.0000, 1.000000, 0, 0, 0, 0, 0.00,
    'INCLUSIVE', 'NONE', 'NOT_APPLICABLE', b'0', 'CASH', 'ACTIVE', 'MANUAL',
    TIMESTAMP '2026-11-20 09:20:00', TIMESTAMP '2026-11-20 09:20:00');

UPDATE expense_report
SET total_amount = total_amount + 5000.0000,
    reimbursable_amount = reimbursable_amount + 5000.0000,
    updated_at = TIMESTAMP '2026-11-20 09:20:00'
WHERE report_id = @report AND employee_id = @emp AND report_status = 'DRAFT';

-- Submission requires a project or client on a trip report (expense.trip.require-project-or-client).
-- Same project as the employee's other dev reports.
UPDATE expense_report
SET project_id = UNHEX('C1E4C0B7E1464322915DE3DACDECF214')
WHERE report_id = @report AND report_status = 'DRAFT' AND project_id IS NULL AND client_id IS NULL;

COMMIT;

-- Check (expect 3 lines, total 5474):
-- SELECT r.report_number, r.total_amount, COUNT(li.line_item_id) lines, SUM(li.amount) line_sum
-- FROM expense_report r JOIN expense_line_item li ON li.report_id = r.report_id
-- WHERE r.report_id = UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf') GROUP BY r.report_id;
