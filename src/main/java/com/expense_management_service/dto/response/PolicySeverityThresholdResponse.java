package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyOverageTier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PolicySeverityThresholdResponse(
        UUID thresholdId,
        UUID policyId,
        PolicyOverageTier tier,
        BigDecimal minPercentOver,
        BigDecimal maxPercentOver,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
