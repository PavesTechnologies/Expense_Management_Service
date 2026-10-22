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
        String remarks,
        /** PENDING, SENT or FAILED; null for a handoff recorded before delivery to AR existed. */
        String arDeliveryStatus,
        Integer arDeliveryAttempts,
        LocalDateTime arDeliveredAt,
        String arDeliveryError
) {
    /** Shape before delivery to AR was tracked. */
    public InvoiceSyncResponse(UUID syncId, UUID lineItemId, String invoiceReference, String syncStatus,
                               LocalDateTime syncDate, Integer retryCount, String remarks) {
        this(syncId, lineItemId, invoiceReference, syncStatus, syncDate, retryCount, remarks, null, null, null, null);
    }
}
