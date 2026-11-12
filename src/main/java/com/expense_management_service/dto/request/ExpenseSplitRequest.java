package com.expense_management_service.dto.request;

import com.expense_management_service.enums.SplitType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One split within a batch {@link ExpenseSplitReplaceRequest}. For {@code splitType == PERCENTAGE},
 * {@code percentage} must be supplied and {@code allocatedAmount} is ignored (the server derives it).
 * For {@code splitType == FIXED_AMOUNT}, {@code allocatedAmount} must be supplied and
 * {@code percentage} must be left null. Every split in one request must share the same
 * {@code splitType} - mixing modes within one line item is not supported.
 */
public record ExpenseSplitRequest(
        @NotNull UUID costCenterId,
        @NotNull SplitType splitType,
        BigDecimal percentage,
        BigDecimal allocatedAmount
) {
}
