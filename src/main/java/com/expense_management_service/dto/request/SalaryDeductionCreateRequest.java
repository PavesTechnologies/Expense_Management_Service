package com.expense_management_service.dto.request;

import java.math.BigDecimal;

/** Finance's request: how much to deduct and in which payroll month. No authorization or payroll reference here. */
public record SalaryDeductionCreateRequest(BigDecimal amount, String payrollMonth) {
}
