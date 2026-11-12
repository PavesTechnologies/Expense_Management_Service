package com.expense_management_service.enums;

/**
 * Outcome of checking one receipt against receipts already claimed elsewhere — see
 * {@code ReceiptDuplicateService}.
 */
public enum DuplicateReceiptStatus {
    /** No claimed receipt matches. */
    NONE,
    /** Same merchant, amount, currency and date as a claimed receipt — submit needs an explanation. */
    WARNING,
    /** Same file, or same invoice from the same supplier, as a claimed receipt — submit is blocked. */
    BLOCKING
}
