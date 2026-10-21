package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AP's confirmation that the reimbursement was actually paid. A report is shown as paid only after
 * this is accepted - never on approval, routing to AP, or a notification.
 *
 * @param paymentReference bank UTR, UPI transaction id or cheque number
 * @param paymentDate      the date the money was actually paid out (today or earlier)
 * @param amountPaid       organization base currency; must equal the amount owed to the employee
 */
public record CompletePaymentRequest(
        @NotBlank @Size(max = 100) String paymentReference,
        @NotNull PaymentMethod paymentMethod,
        @NotNull @PastOrPresent LocalDate paymentDate,
        @NotNull @Positive BigDecimal amountPaid,
        @Size(max = 500) String remarks
) {
}
