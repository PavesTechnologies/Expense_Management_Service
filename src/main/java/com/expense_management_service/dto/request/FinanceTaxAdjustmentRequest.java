package com.expense_management_service.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance correction of one line's tax. The gross amount never changes this way (that is a
 * correction request). {@code taxCodeId} null keeps the line's code; {@code taxAmount} null takes
 * the code's calculation; {@code itcRecoverablePercent} null keeps the code's ITC %.
 */
public record FinanceTaxAdjustmentRequest(
        UUID taxCodeId,
        @PositiveOrZero BigDecimal taxAmount,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal itcRecoverablePercent,
        @NotBlank @Size(max = 500) String reason
) {
}
