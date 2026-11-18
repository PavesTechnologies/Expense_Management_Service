package com.expense_management_service.dto.request;

import com.expense_management_service.enums.EmployeePaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One missing expense on a supplementary claim. The currency is the claim's currency, so it is not sent. */
public record SupplementaryClaimItemRequest(
        @NotNull LocalDate expenseDate,
        @NotNull UUID categoryId,
        @Size(max = 255) String merchantName,
        @Size(max = 500) String description,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        EmployeePaymentMethod employeePaymentMethod,
        @Size(max = 500) String notes
) {
}
