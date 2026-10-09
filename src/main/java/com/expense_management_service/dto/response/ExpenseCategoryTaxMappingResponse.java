package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ExpenseCategoryTaxMappingResponse(
        UUID mappingId,
        UUID categoryId,
        UUID taxCodeId,
        String taxCode,
        String taxName,
        BigDecimal ratePercent,
        String taxCodeStatus,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        /** PAST, CURRENT or SCHEDULED, relative to today. */
        String state,
        String createdBy,
        LocalDateTime createdAt
) {
}
