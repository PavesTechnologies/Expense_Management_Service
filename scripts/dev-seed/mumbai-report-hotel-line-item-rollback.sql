-- DEV ONLY. Undoes mumbai-report-hotel-line-item.sql: restores the report total to 474 INR and removes the
-- 5000 INR hotel line item. Safe to run twice (the total is only reduced while the line still exists).

START TRANSACTION;

UPDATE expense_report
SET total_amount = total_amount - 5000.0000,
    reimbursable_amount = reimbursable_amount - 5000.0000
WHERE report_id = UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf')
  AND EXISTS (SELECT 1 FROM expense_line_item WHERE line_item_id = UNHEX('c7a1e3f05b2d4e8a9f61d2b47c03a915'));

DELETE FROM expense_line_item WHERE line_item_id = UNHEX('c7a1e3f05b2d4e8a9f61d2b47c03a915');

-- Only while still a draft; a submitted report keeps its project.
UPDATE expense_report SET project_id = NULL
WHERE report_id = UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf') AND report_status = 'DRAFT';

COMMIT;
