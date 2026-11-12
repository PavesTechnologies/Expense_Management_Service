package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PolicyRuleResponse(
        UUID policyId,
        UUID policyBundleId,
        UUID categoryId,
        String categoryName,
        String policyName,
        PolicyRuleType ruleType,
        String ruleValue,
        PolicySeverity severity,
        PolicyEnforcementType enforcementType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** Empty when the rule is in legacy flat-limit mode - see {@code PolicyRuleRequest}'s javadoc. */
        List<PolicyRuleLimitResponse> limits
) {
}
