package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CashAdvanceResponse(
        UUID advanceId,
        String employeeId,
        BigDecimal amount,
        UUID currencyId,
        String currencyCode,
        BigDecimal baseAmount,
        String purpose,
        String status,
        LocalDate settlementDueDate,
        BigDecimal outstandingBalance
) {
}
