package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseCategoryRequest(
        @NotBlank @Size(max = 255) String categoryCode,
        @NotBlank @Size(max = 255) String categoryName,
        @NotNull UUID glAccountId,
        String description,
        Boolean receiptRequired,
        BigDecimal maxLimit,
        @Size(max = 255) String taxCode,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 255) String status
) {
}
