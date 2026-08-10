package com.expense_management_service.dto.response;

import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyOverageTier;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A compliance flag on a line item. {@code enforcementType} determines whether it can ever gate
 * submission (only {@code ApprovalWorkflowServiceImpl.submit()} acts on that — see
 * PolicyEvaluator's javadoc for the never-throw contract this is produced under). {@code
 * limitValue}/{@code actualValue}/{@code overagePercent}/{@code severityTier}/{@code
 * currencyCode} are populated only for {@code AMOUNT_LIMIT} violations - null for every other rule
 * type, giving "over by ₹700 (Moderate)" instead of a bare pass/fail flag.
 */
public record PolicyWarningResponse(
        UUID violationId,
        PolicyRuleType ruleType,
        PolicySeverity severity,
        PolicyEnforcementType enforcementType,
        String message,
        BigDecimal limitValue,
        BigDecimal actualValue,
        BigDecimal overagePercent,
        PolicyOverageTier severityTier,
        String currencyCode,
        String justification,
        LocalDateTime justifiedAt,
        /** Which numbered policy version was active when this was detected - see {@code PolicyVersion}'s javadoc. */
        Integer policyVersionNumber
) {
}
