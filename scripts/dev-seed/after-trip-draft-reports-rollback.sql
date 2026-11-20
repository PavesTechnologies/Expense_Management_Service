-- DEV ONLY. Removes the three draft reports from after-trip-draft-reports.sql and their line items, by fixed id.
-- If you added receipts or more line items to these reports in the app, delete those first.

START TRANSACTION;

DELETE FROM expense_line_item
WHERE report_id IN (
    UNHEX('90d55959455941dda87ce4c97d0dbe40'),
    UNHEX('daccd2089a1b47469fcbeb4ea8f2d817'),
    UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf'));

DELETE FROM expense_report
WHERE report_id IN (
    UNHEX('90d55959455941dda87ce4c97d0dbe40'),
    UNHEX('daccd2089a1b47469fcbeb4ea8f2d817'),
    UNHEX('36c1caa7cfaa4645b06d3ba536ec2adf'));

COMMIT;
