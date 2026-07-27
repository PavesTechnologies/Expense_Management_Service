package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Employee-facing request to create/update a Draft expense report.
 * <p>
 * {@code employeeId}, {@code reportNumber}, {@code reportStatus} and the monetary/workflow
 * timestamp fields are intentionally absent — they are server-derived (owner from the JWT,
 * number auto-generated, status forced to Draft on create) to prevent mass-assignment of
 * read-only/system-owned fields.
 */
public record ExpenseReportRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String businessPurpose,
        @NotNull UUID costCenterId,
        @NotNull UUID currencyId
) {
}
