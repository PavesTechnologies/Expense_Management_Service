package com.expense_management_service.enums;

/**
 * Downstream payment/invoice transport status for an {@code ExpenseReport}, deliberately kept
 * independent of {@code ReportStatus}: a Reimbursement/Invoice integration failure must never be
 * able to corrupt a report's completed approval/Finance-verification record.
 */
public enum PaymentRoutingStatus {
    /** No routing decision made yet - the report hasn't finished Finance verification (or has no Finance level). */
    NONE,
    APPROVED_FOR_PAYMENT,
    INVOICE_HANDOFF_PENDING,
    INVOICE_HANDOFF_COMPLETED,
    /** Handoff to the downstream module failed; retried independently of the approval/Finance state. */
    HANDOFF_FAILED
}
