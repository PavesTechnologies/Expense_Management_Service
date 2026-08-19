package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One report in AP_EXECUTIVE's payment queue - internal expenses awaiting external payment confirmation (never client-billable, those route to INVOICE_HANDOFF_PENDING instead). */
public record ApPaymentQueueItemResponse(
        UUID reportId,
        String reportNumber,
        String employeeId,
        String title,
        BigDecimal totalAmount,
        String currencyCode,
        UUID costCenterId,
        String costCenterName,
        LocalDateTime approvedAt,
        String reportStatus,
        String paymentRoutingStatus
) {
}
