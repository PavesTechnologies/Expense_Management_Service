package com.expense_management_service.dto.request;

import com.expense_management_service.enums.TaxType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create / update a tax code. {@code components} may be omitted for CGST_SGST and IGST, which are
 * generated from {@code ratePercent} (CGST_SGST 18 -> CGST 9 + SGST 9); when given,
 * {@code ratePercent} may be omitted and is derived as their sum.
 * {@code itcRecoverablePercent} wins over the legacy {@code itcEligible} flag (true = 100%).
 * {@code changeReason} is required when deactivating a code.
 */
public record TaxCodeRequest(
        @NotBlank @Size(max = 50) String taxCode,
        @NotBlank @Size(max = 255) String taxName,
        @NotNull TaxType taxType,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ratePercent,
        Boolean itcEligible,
        UUID inputTaxGlAccountId,
        String description,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 32) String status,
        @Size(max = 2) String countryCode,
        @Size(max = 16) String regionCode,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal itcRecoverablePercent,
        List<@Valid TaxCodeComponentRequest> components,
        @Size(max = 500) String changeReason
) {
}
