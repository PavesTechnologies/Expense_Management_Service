package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One report in AP_EXECUTIVE's payment queue - every approved report awaiting external payment
 * confirmation, client-billable included. {@code invoiceHandoffStatus} is informational only for AP
 * (whether the invoice team also has this report), it never gates payment.
 */
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
        String paymentRoutingStatus,
        String invoiceHandoffStatus,
        /** Set once AP confirms the payment (PAYMENT_COMPLETED), else null. */
        LocalDateTime paymentCompletedAt,
        String paymentCompletedBy,
        /** Base currency: gross (= totalAmount), tax, recoverable tax and what AP pays the employee. */
        BigDecimal grossAmount,
        BigDecimal taxAmount,
        BigDecimal recoverableTaxAmount,
        BigDecimal reimbursableAmount
) {
}
