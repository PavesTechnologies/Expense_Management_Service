package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * A lightweight, free-text explanation an employee attaches to a {@code PolicyViolation}. This
 * annotates the warning — it does not clear or suppress it; the approver sees both the original
 * flag and this note. Minimum length is enforced in the service layer via
 * {@code policy.justification.min-length}, matching how {@code ExpenseReportServiceImpl} validates
 * businessPurpose's minimum length.
 */
public record PolicyJustificationRequest(
        @NotBlank String justification
) {
}
