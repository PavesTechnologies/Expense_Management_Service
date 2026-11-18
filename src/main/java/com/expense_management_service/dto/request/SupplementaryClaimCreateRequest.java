package com.expense_management_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Creates a supplementary claim against a settled report.
 *
 * <p>{@code employeeId} is used only by Finance when creating a claim on behalf of an employee; the employee self-service
 * endpoint ignores it.
 */
public record SupplementaryClaimCreateRequest(
        @NotNull UUID reportId,
        @NotBlank @Size(max = 40) String lateReasonCode,
        @Size(max = 2000) String lateReasonText,
        @Size(max = 500) String creationReason,
        @Size(max = 255) String employeeId,
        @Valid List<SupplementaryClaimItemRequest> items
) {
}
