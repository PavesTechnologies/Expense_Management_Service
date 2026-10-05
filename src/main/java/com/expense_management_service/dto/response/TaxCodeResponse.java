package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record TaxCodeResponse(
        UUID taxCodeId,
        String taxCode,
        String taxName,
        String taxType,
        /** Sum of the component rates. */
        BigDecimal ratePercent,
        /** Derived: itcRecoverablePercent > 0. */
        Boolean itcEligible,
        UUID inputTaxGlAccountId,
        String inputTaxGlAccountName,
        String description,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** Categories mapped to this code today or from a scheduled date. */
        long mappedCategoryCount,
        String countryCode,
        String regionCode,
        BigDecimal itcRecoverablePercent,
        List<TaxCodeComponentResponse> components,
        /** Expense lines that have snapshotted this code. */
        long usageCount,
        /** True once used: type, rate, components and ITC % can no longer change. */
        boolean locked
) {
}
