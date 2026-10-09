package com.expense_management_service.dto.request;

import com.expense_management_service.enums.TaxComponentCode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** One levied part of a tax code; {@code label} defaults to e.g. "CGST 9%". */
public record TaxCodeComponentRequest(
        @NotNull TaxComponentCode componentCode,
        @Size(max = 100) String label,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax("100.00") BigDecimal ratePercent,
        UUID glAccountId
) {
}
