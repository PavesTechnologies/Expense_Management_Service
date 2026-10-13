package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Input tax credit register: every submitted line with tax in the window (expense date), split into
 * recoverable / non-recoverable, with whether Finance has verified the credit and the supplier GSTIN
 * it rests on - what Finance reconciles against the GST return. Amounts are base currency.
 */
public record ItcRegisterResponse(
        LocalDate from,
        LocalDate to,
        String currency,
        List<Line> lines,
        Totals totals
) {
    public record Line(UUID lineItemId, UUID reportId, String reportNumber, String employeeId, String reportStatus,
                       LocalDate expenseDate, String merchantName, String categoryName, String taxCode, String supplierGstin,
                       BigDecimal taxAmount, BigDecimal itcRecoverablePercent, BigDecimal recoverableTaxAmount,
                       BigDecimal nonRecoverableTaxAmount, String taxValidationStatus,
                       /** NOT_RECOVERABLE, UNVERIFIED or VERIFIED - see ItcStatus. */
                       String itcStatus) {
    }

    /**
     * {@code verified + unverified = recoverable}; {@code recoverable + nonRecoverable = tax}.
     * {@code recoverableWithoutGstinCount}: lines claiming credit with no supplier GSTIN on record.
     */
    public record Totals(long lineCount, BigDecimal taxAmount, BigDecimal recoverableTaxAmount,
                         BigDecimal verifiedRecoverableTaxAmount, BigDecimal unverifiedRecoverableTaxAmount,
                         BigDecimal nonRecoverableTaxAmount, long recoverableWithoutGstinCount) {
    }
}
