package com.expense_management_service.enums;

/** Why Finance sent a line back for correction, so the employee knows what to fix. */
public enum CorrectionCategory {
    /** The receipt's tax (amount, rate or CGST/SGST/IGST split) doesn't match the expense. */
    TAX_MISMATCH,
    /** Receipt missing, unreadable or not matching the expense. */
    RECEIPT,
    /** The amount doesn't match the receipt. */
    AMOUNT,
    OTHER
}
