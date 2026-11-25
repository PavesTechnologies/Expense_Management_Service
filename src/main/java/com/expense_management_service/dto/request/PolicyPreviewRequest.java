package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The line-item form as the employee is filling it in, sent to the policy preview so warnings
 * ("over limit", "receipt required", ...) show before the line is saved. Nothing is persisted.
 * <p>
 * Deliberately looser than {@link ExpenseLineItemRequest}: the form may be half-filled, so only
 * what policy evaluation cannot do without is required.
 *
 * @param lineItemId      the line being edited, or null for a new one. Lets the duplicate checks
 *                        ignore the line itself and keeps its already-attached receipts in view.
 * @param receiptAttached whether the form currently holds a receipt (a scanned or picked file not
 *                        yet linked to a line). Advisory only: the authoritative receipt check runs
 *                        again on save.
 */
public record PolicyPreviewRequest(
        @NotNull UUID categoryId,
        LocalDate expenseDate,
        String merchantName,
        String description,
        @NotNull @PositiveOrZero BigDecimal amount,
        @NotNull UUID currencyId,
        UUID lineItemId,
        Boolean receiptAttached
) {
}
