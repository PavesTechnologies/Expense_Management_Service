package com.expense_management_service.enums;

/**
 * How the employee originally paid for an expense line (Stage 1, S1-11 to S1-13). This is the employee's own
 * payment instrument, not the company's reimbursement method (that is {@link PaymentMethod}, recorded by AP).
 * {@link #COMPANY_CARD} lines were paid by the company, so they never enter the employee's reimbursable total.
 */
public enum EmployeePaymentMethod {
    CASH,
    PERSONAL_CARD,
    UPI,
    /** Paid with a company card: tracked for reconciliation only, excluded from the reimbursable total. */
    COMPANY_CARD,
    /** Any other instrument; the line's description must explain it. */
    OTHER
}
