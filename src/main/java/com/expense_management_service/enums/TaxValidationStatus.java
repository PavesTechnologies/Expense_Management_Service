package com.expense_management_service.enums;

/**
 * One status per line describing how its tax checks out; the detail is in the line's reason codes.
 * See the tax design's state machine: CALCULATED lines become MATCHED / WARNING / MISMATCH once
 * compared, exceptions are promoted to REQUIRES_FINANCE_REVIEW at submission, and Finance ends
 * in FINANCE_VERIFIED or FINANCE_ADJUSTED.
 */
public enum TaxValidationStatus {
    NOT_APPLICABLE,
    CONFIGURATION_MISSING,
    CALCULATED,
    MATCHED,
    WARNING,
    MISMATCH,
    REQUIRES_FINANCE_REVIEW,
    FINANCE_VERIFIED,
    FINANCE_ADJUSTED,
    LEGACY
}
