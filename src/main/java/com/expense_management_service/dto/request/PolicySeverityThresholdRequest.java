package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PolicyOverageTier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** {@code maxPercentOver} null means open-ended - only valid for the highest tier in a scope's band set. */
public record PolicySeverityThresholdRequest(
        @NotNull PolicyOverageTier tier,
        @NotNull @PositiveOrZero BigDecimal minPercentOver,
        BigDecimal maxPercentOver
) {
}
