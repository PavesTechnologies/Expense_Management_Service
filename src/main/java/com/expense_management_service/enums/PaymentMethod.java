package com.expense_management_service.enums;

/** How AP actually paid an employee's reimbursement, as recorded when AP confirms the payment. */
public enum PaymentMethod {
    /** NEFT / RTGS / IMPS or any bank-to-bank transfer — reference is the UTR. */
    BANK_TRANSFER,
    UPI,
    CHEQUE,
    CASH,
    OTHER
}
