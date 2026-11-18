package com.expense_management_service.dto.request;

import com.expense_management_service.enums.PaymentMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** AP confirms that a supplementary claim was paid. The bank reference must be unique across every payment. */
public record SupplementaryPaymentConfirmRequest(
        @NotBlank @Size(min = 6, max = 100) String bankReference,
        @NotNull PaymentMethod paymentMethod,
        @NotNull @PastOrPresent LocalDate paymentDate,
        @NotNull @Positive BigDecimal amountPaid,
        @Size(max = 500) String remarks
) {
}
