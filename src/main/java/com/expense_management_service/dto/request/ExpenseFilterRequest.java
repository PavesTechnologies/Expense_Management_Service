package com.expense_management_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import java.time.LocalDate;

/**
 * EP12-S2: Advanced Multi-Criteria Filter Request
 * Supports precise, structured narrowing of expense data by combining multiple criteria.
 *
 * Validation:
 * - Amount range: amountMin must be <= amountMax (if both provided)
 * - Date range: dateFrom must be <= dateTo (if both provided)
 */
public record ExpenseFilterRequest(
        String status,           // DRAFT, SUBMITTED, APPROVED, REJECTED, REIMBURSED
        LocalDate dateFrom,      // Filter reports created from this date
        LocalDate dateTo,        // Filter reports created until this date
        String category,         // Expense category
        String costCenter,       // Cost center UUID or name

        @DecimalMin(value = "0.0", inclusive = true)
        Double amountMin,        // Minimum amount (validates min <= max)

        @DecimalMin(value = "0.0", inclusive = true)
        Double amountMax,        // Maximum amount (validates min <= max)

        String employeeId,       // Filter by employee (scoped by role)
        Boolean billable         // Billable/non-billable expenses
) {
}
