-- Client-billable reports were being routed to payment_routing_status = INVOICE_HANDOFF_PENDING
-- INSTEAD OF APPROVED_FOR_PAYMENT, so they never reached the AP payment queue and the employee was
-- never reimbursed. Invoicing the client and reimbursing the employee are two independent tracks,
-- so invoice progress now lives in its own column (ExpenseReport.invoiceHandoffStatus) and every
-- approved report goes to AP.
--
-- VARCHAR, not a native ENUM: see V14 for how an ENUM column drifts out of sync with the Java enum
-- under ddl-auto=update.
ALTER TABLE expense_report
    ADD COLUMN invoice_handoff_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE';

-- Move reports already stuck on the old combined status onto both tracks.
UPDATE expense_report
SET invoice_handoff_status = 'PENDING',
    payment_routing_status = 'APPROVED_FOR_PAYMENT'
WHERE payment_routing_status = 'INVOICE_HANDOFF_PENDING';

UPDATE expense_report
SET invoice_handoff_status = 'COMPLETED',
    payment_routing_status = 'APPROVED_FOR_PAYMENT'
WHERE payment_routing_status = 'INVOICE_HANDOFF_COMPLETED';
