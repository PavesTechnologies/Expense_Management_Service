package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code ruleValue}'s meaning depends on {@code ruleType}: a decimal ceiling (e.g. "500.00") for
 * {@code AMOUNT_LIMIT}, an integer day count (e.g. "30") for {@code BACKDATED_DAYS}, unused for
 * {@code RECEIPT_REQUIRED}/{@code MISSING_DESCRIPTION}/{@code DUPLICATE_EXPENSE}. {@code severity}
 * defaults to {@code WARN} when omitted — only {@code DUPLICATE_EXPENSE} conventionally uses
 * {@code INFO}, but that is an admin choice, not an enforced default.
 */
public record PolicyRuleRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 255) String policyName,
        @NotNull PolicyRuleType ruleType,
        @Size(max = 255) String ruleValue,
        PolicySeverity severity,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 255) String status
) {
}
