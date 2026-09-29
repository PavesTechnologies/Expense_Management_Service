package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * An approver's own documented reason for authorizing an expense despite an open {@code
 * PolicyViolation} — distinct from {@link PolicyJustificationRequest}, which is the report owner's
 * own note. Recorded via {@code PolicyViolationServiceImpl.approveException()} by whoever is an
 * active approver (or delegate) for the report's current ACTIVE approval-level instance. Minimum
 * length is enforced in the service layer via the same {@code policy.justification.min-length}
 * property {@code justify()} uses.
 */
public record ApproverExceptionRequest(
        @NotBlank String justification
) {
}
