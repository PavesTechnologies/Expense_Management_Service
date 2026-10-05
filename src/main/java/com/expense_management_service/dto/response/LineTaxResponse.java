package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** A line's tax snapshot. Amounts are in the line's currency unless prefixed with base. */
public record LineTaxResponse(
        UUID taxCodeId,
        String taxCode,
        String taxType,
        BigDecimal taxRatePercent,
        String taxTreatment,
        BigDecimal taxableAmount,
        BigDecimal taxAmount,
        BigDecimal calculatedTaxAmount,
        String taxSource,
        String taxOverrideReason,
        BigDecimal itcRecoverablePercent,
        BigDecimal recoverableTaxAmount,
        BigDecimal baseTaxAmount,
        BigDecimal baseNetAmount,
        BigDecimal baseRecoverableTaxAmount,
        BigDecimal ocrTaxAmount,
        String validationStatus,
        List<String> validationReasons,
        /** When the snapshot was frozen (submission); null while editable. */
        LocalDateTime snapshotAt,
        List<LineTaxComponentResponse> components
) {
}
