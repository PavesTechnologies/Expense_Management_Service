package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Result of POST /xms/tax/calculate - the same engine line saves use. */
public record TaxCalculationResponse(
        UUID taxCodeId,
        String taxCode,
        String taxName,
        String taxType,
        BigDecimal ratePercent,
        String taxTreatment,
        BigDecimal amount,
        /** Amount before tax. */
        BigDecimal taxableAmount,
        /** Final tax: the calculation, or the entered value when it differs beyond the tolerance. */
        BigDecimal taxAmount,
        BigDecimal calculatedTaxAmount,
        /** entered - calculated; null without a code. */
        BigDecimal difference,
        BigDecimal tolerance,
        /** The entered tax differs beyond rounding, so a reason is needed before submission. */
        boolean overrideReasonRequired,
        String taxSource,
        String validationStatus,
        List<String> validationReasons,
        BigDecimal itcRecoverablePercent,
        BigDecimal recoverableTaxAmount,
        List<LineTaxComponentResponse> components
) {
}
