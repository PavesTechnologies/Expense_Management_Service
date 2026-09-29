package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record TaxCodeResponse(
        UUID taxCodeId,
        String taxCode,
        String taxName,
        String taxType,
        BigDecimal ratePercent,
        Boolean itcEligible,
        UUID inputTaxGlAccountId,
        String inputTaxGlAccountName,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long mappedCategoryCount
) {
}
