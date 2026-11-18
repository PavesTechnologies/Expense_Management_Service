package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SupplementaryClaimItemResponse(
        UUID claimItemId,
        LocalDate expenseDate,
        UUID categoryId,
        String merchantName,
        String description,
        BigDecimal amount,
        String currencyCode,
        String employeePaymentMethod,
        String notes,
        List<SupplementaryReceiptResponse> receipts
) {
}
