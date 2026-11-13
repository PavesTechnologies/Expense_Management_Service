package com.expense_management_service.dto.request;

import com.expense_management_service.enums.CashAdvanceRequestType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the employee has entered so far. {@code amount} is the full amount for COMPLETE_NEW, and the additional amount for
 * ADVANCE_PLUS_OUTSTANDING. The preview is informational; the server recalculates everything on create and submit.
 */
public record CashAdvancePreviewRequest(
        CashAdvanceRequestType requestType,
        @NotNull @Positive BigDecimal amount,
        UUID currencyId
) {
}
