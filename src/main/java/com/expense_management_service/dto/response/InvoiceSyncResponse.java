package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record InvoiceSyncResponse(
        UUID syncId,
        UUID lineItemId,
        String invoiceReference,
        String syncStatus,
        LocalDateTime syncDate,
        Integer retryCount,
        String remarks
) {
}
