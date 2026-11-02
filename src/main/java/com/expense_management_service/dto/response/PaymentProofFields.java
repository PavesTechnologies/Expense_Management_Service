package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Fields read from a payment screenshot. Any field the screenshot does not show is null. */
public record PaymentProofFields(
        String reference,
        LocalDate transferDate,
        String payeeUpiId,
        BigDecimal amount
) {
}
