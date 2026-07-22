package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record InvoiceSyncRequest(
        @NotNull UUID lineItemId,
        @Size(max = 255) String invoiceReference,
        @Size(max = 255) String syncStatus,
        @PositiveOrZero Integer retryCount,
        String remarks
) {
}
