package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;

import java.time.LocalDateTime;
import java.util.UUID;

/** An advisory-only compliance flag on a line item — never a block. See PolicyEvaluator's javadoc. */
public record PolicyWarningResponse(
        UUID violationId,
        PolicyRuleType ruleType,
        PolicySeverity severity,
        String message,
        String justification,
        LocalDateTime justifiedAt
) {
}
