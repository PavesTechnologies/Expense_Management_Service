package com.expense_management_service.dto.request;

import java.math.BigDecimal;

/**
 * Finance's request: how much to deduct and in which payroll month. No authorization or payroll reference here.
 * {@code amountChangeReason} is required when the amount differs from the full outstanding balance.
 */
public record SalaryDeductionCreateRequest(BigDecimal amount, String payrollMonth, String amountChangeReason) {
}
