package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
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
        UUID cashAdvanceId,
        /** Start date of the trip or client meeting. */
        LocalDate tripStartDate,
        /** Estimated trip or client-meeting end date. Required before the report can be submitted. */
        LocalDate tripEndDate,
        /** Actual reporting period start. Optional. */
        LocalDate reportingPeriodStart,
        /** Actual reporting period end. Optional, but may not be in the future. */
        LocalDate reportingPeriodEnd,
        /** The internal project the trip is for. At least one of project or client is required before submission (Stage 1, S1-15). */
        UUID projectId,
        /** The RMS client the trip is for. At least one of project or client is required before submission (Stage 1, S1-15). */
        UUID clientId
) {

    /** Shape before the project and client reference were added: both are null. */
    public ExpenseReportRequest(String title, String businessPurpose, UUID costCenterId, UUID currencyId, UUID cashAdvanceId,
                                LocalDate tripStartDate, LocalDate tripEndDate, LocalDate reportingPeriodStart,
                                LocalDate reportingPeriodEnd) {
        this(title, businessPurpose, costCenterId, currencyId, cashAdvanceId, tripStartDate, tripEndDate,
                reportingPeriodStart, reportingPeriodEnd, null, null);
    }

    /** Report without a cash advance link or trip dates. */
    public ExpenseReportRequest(String title, String businessPurpose, UUID costCenterId, UUID currencyId) {
        this(title, businessPurpose, costCenterId, currencyId, null, null, null, null, null);
    }

    /** Report with a cash advance link and no trip dates. */
    public ExpenseReportRequest(String title, String businessPurpose, UUID costCenterId, UUID currencyId,
                                UUID cashAdvanceId) {
        this(title, businessPurpose, costCenterId, currencyId, cashAdvanceId, null, null, null, null);
    }
}
