package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to add/edit an expense line item under a Draft (or Policy-Rejected/Query-Raised)
 * expense report. {@code reportId} is deliberately absent — the parent report is taken from
 * the path, never the body, so a line item can never be attached to a report the caller
 * doesn't own by mismatching path/body identifiers.
 * <p>
 * {@code costCenterId} and {@code projectId} are both optional — a line item may inherit
 * the report's default cost center and may not belong to any project.
 * <p>
 * {@code amount} is the total amount exactly as printed on the receipt (inclusive of any
 * VAT/GST); {@code taxAmount} is the VAT/GST portion of it, entered exactly as printed —
 * the service never derives it from a percentage. A {@code null} taxAmount is treated as
 * zero. The server derives {@code netAmount = amount - taxAmount}; it is not accepted here.
 */
public record ExpenseLineItemRequest(
        @NotNull UUID categoryId,
        @NotNull LocalDate expenseDate,
        @Size(max = 255) String merchantName,
        String description,
        @NotNull @Positive BigDecimal amount,
        @NotNull UUID currencyId,
        @PositiveOrZero BigDecimal taxAmount,
        UUID costCenterId,
        UUID projectId,
        Boolean clientBillable,
        /** Explicit tax code; null = the category's mapping on the expense date. */
        UUID taxCodeId,
        /** Why the entered tax differs from the tax code's calculation; required at submission when it does. */
        @Size(max = 500) String taxOverrideReason
) {
    /** Pre-tax-code shape: {@code taxAmount} is compared with the calculation for the category's code. */
    public ExpenseLineItemRequest(UUID categoryId, LocalDate expenseDate, String merchantName, String description,
                                  BigDecimal amount, UUID currencyId, BigDecimal taxAmount, UUID costCenterId,
                                  UUID projectId, Boolean clientBillable) {
        this(categoryId, expenseDate, merchantName, description, amount, currencyId, taxAmount, costCenterId,
                projectId, clientBillable, null, null);
    }
}
