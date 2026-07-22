package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record PolicyRuleRequest(
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 255) String policyName,
        @Size(max = 255) String ruleType,
        @Size(max = 255) String ruleValue,
        @Size(max = 255) String action,
        @Size(max = 255) String severity,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 255) String status
) {
}
