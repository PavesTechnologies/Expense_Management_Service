package com.expense_management_service.enums;

import java.math.BigDecimal;

/**
 * Where a line's input tax credit stands - derived from the snapshot, never stored, so it cannot
 * drift from the line's recoverable amount or its Finance tax status.
 */
public enum ItcStatus {
    /** Nothing to claim: no tax, or the tax code's ITC % is 0 (blocked credit, exempt, legacy line). */
    NOT_RECOVERABLE,
    /** Recoverable tax that Finance has not verified yet (still at Finance, or approved by a flow with no Finance level). */
    UNVERIFIED,
    /** Recoverable tax Finance verified or adjusted - claimable. */
    VERIFIED;

    public static ItcStatus of(BigDecimal recoverableTaxAmount, TaxValidationStatus taxValidationStatus) {
        if (recoverableTaxAmount == null || recoverableTaxAmount.signum() <= 0) {
            return NOT_RECOVERABLE;
        }
        return taxValidationStatus == TaxValidationStatus.FINANCE_VERIFIED || taxValidationStatus == TaxValidationStatus.FINANCE_ADJUSTED
                ? VERIFIED : UNVERIFIED;
    }
}
