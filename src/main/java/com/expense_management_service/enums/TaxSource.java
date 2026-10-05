package com.expense_management_service.enums;

/** Where a line's final {@code tax_amount} came from. */
public enum TaxSource {
    CALCULATED,
    EMPLOYEE_OVERRIDE,
    OCR,
    FINANCE_ADJUSTED,
    /** Entered before tax codes existed on lines; no code, rate or ITC is inferred for it. */
    LEGACY,
    NONE
}
