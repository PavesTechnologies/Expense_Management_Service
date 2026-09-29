package com.expense_management_service.dto.request;

import com.expense_management_service.enums.TaxType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TaxCodeRequest(
        @NotBlank @Size(max = 50) String taxCode,
        @NotBlank @Size(max = 255) String taxName,
        @NotNull TaxType taxType,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ratePercent,
        Boolean itcEligible,
        UUID inputTaxGlAccountId,
        String description,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 32) String status
) {
}
