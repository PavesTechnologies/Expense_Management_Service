package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record PolicyRuleResponse(
        UUID policyId,
        UUID categoryId,
        String categoryName,
        String policyName,
        PolicyRuleType ruleType,
        String ruleValue,
        PolicySeverity severity,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
