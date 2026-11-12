package com.expense_management_service.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Canonical repayment input, built by the controller from the multipart form
 * ({@code POST /xms/employee/cash-advances/{advanceId}/repayments}, multipart/form-data only).
 *
 * <p>{@code payment} carries how the money was paid back. It uses the same shape and rules as a disbursement
 * ({@link CashAdvanceDisbursement}): CASH needs the receiver and a signed acknowledgment, BANK_TRANSFER needs a
 * transaction reference, date and transfer details, UPI needs the UPI transaction ID, payee UPI ID, date and a
 * payment screenshot. Nothing is recorded unless these rules pass.
 */
public record CashAdvanceRepaymentRequest(
        UUID advanceId,
        BigDecimal amount,
        String notes,
        CashAdvanceDisbursement payment
) {
}
