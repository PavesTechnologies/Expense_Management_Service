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
 * <p>
 * {@code cashAdvanceId} is optional. When present on create, the report is linked to that advance
 * so its verified expenses can later be offset against it. The server checks that the advance
 * belongs to the caller and is in a post-disbursement status.
 */
public record ExpenseReportRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String businessPurpose,
        @NotNull UUID costCenterId,
        @NotNull UUID currencyId,
        UUID cashAdvanceId
) {

    /** Report without a cash advance link. */
    public ExpenseReportRequest(String title, String businessPurpose, UUID costCenterId, UUID currencyId) {
        this(title, businessPurpose, costCenterId, currencyId, null);
    }
}
