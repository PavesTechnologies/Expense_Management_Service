package com.expense_management_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Finance sets or corrects the supplier GSTIN a line's input tax credit rests on, while the line
 * awaits Finance verification. {@code supplierGstin} blank clears it. Tax amounts never change this way.
 */
public record FinanceSupplierGstinRequest(
        @Size(max = 20) String supplierGstin,
        @NotBlank @Size(max = 500) String reason
) {
}
