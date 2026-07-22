package com.expense_management_service.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record PolicyRuleResponse(
        UUID policyId,
        UUID categoryId,
        String categoryName,
        String policyName,
        String ruleType,
        String ruleValue,
        String action,
        String severity,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status
) {
}
