package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code ruleValue}'s meaning depends on {@code ruleType}: a decimal ceiling (e.g. "500.00") for
 * {@code AMOUNT_LIMIT}, an integer day count (e.g. "30") for {@code BACKDATED_DAYS}, unused for
 * {@code RECEIPT_REQUIRED}/{@code MISSING_DESCRIPTION}/{@code DUPLICATE_EXPENSE}. {@code severity}
 * defaults to {@code WARN} when omitted — only {@code DUPLICATE_EXPENSE} conventionally uses
 * {@code INFO}, but that is an admin choice, not an enforced default. {@code policyBundleId} is
 * optional — omitted, it defaults to the seeded "Default Policy" bundle, so any client built
 * before the Policy bundle model existed keeps working unmodified. {@code enforcementType}
 * defaults to {@code WARN} when omitted, matching the "use Block sparingly" guidance - Admin must
 * opt in to Block explicitly, it is never the silent default for a new rule. {@code limits} is
 * only meaningful for {@code AMOUNT_LIMIT} rules - null or empty (the default) keeps the rule in
 * legacy flat-limit mode using {@code ruleValue}; a non-empty list replaces the rule's entire
 * currency-limit set and switches it into per-currency mode. See {@code
 * DefaultPolicyEvaluator#checkAmountLimit} for the precedence between the two modes.
 */
public record PolicyRuleRequest(
        UUID policyBundleId,
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 255) String policyName,
        @NotNull PolicyRuleType ruleType,
        @Size(max = 255) String ruleValue,
        PolicySeverity severity,
        PolicyEnforcementType enforcementType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 255) String status,
        @Valid List<PolicyRuleLimitRequest> limits
) {
}
