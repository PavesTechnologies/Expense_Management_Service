package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Codes an employee may pick for an expense date, plus the category's default (null = no tax). */
public record ApplicableTaxCodesResponse(
        UUID defaultTaxCodeId,
        String defaultTaxCode,
        List<Option> options
) {
    public record Option(UUID taxCodeId, String taxCode, String taxName, String taxType, BigDecimal ratePercent,
                         /** e.g. "CGST 9% + SGST 9%". */ String componentSummary) {
    }
}
