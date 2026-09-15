package com.expense_management_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Replaces the ENTIRE set of splits on one line item in a single, atomic operation - never a
 * per-row create/update/delete. List order is the entry order (drives {@code ExpenseSplit.splitOrder}
 * and Rule 5's "absorb rounding into the last split"). An empty list reverts the line item to NORMAL
 * (zero splits); any other size below 2 is rejected.
 */
public record ExpenseSplitReplaceRequest(
        @NotNull List<@Valid ExpenseSplitRequest> splits
) {
}
