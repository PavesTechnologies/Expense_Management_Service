package com.expense_management_service.enums;

/**
 * Client-invoicing status for an {@code ExpenseReport}, deliberately independent of both {@code
 * ReportStatus} and {@code PaymentRoutingStatus}: a client-billable report is still an employee
 * reimbursement (AP pays the employee) AND separately needs to reach the invoice team so the
 * client can be billed — the two tracks progress on their own and neither blocks the other.
 */
public enum InvoiceHandoffStatus {
    /** No client-billable line items, or the report hasn't finished approval yet. */
    NOT_APPLICABLE,
    /** Approved with ≥1 client-billable line item not yet handed off to the invoice team. */
    PENDING,
    /** Every client-billable line item on the report has been handed off. */
    COMPLETED
}
