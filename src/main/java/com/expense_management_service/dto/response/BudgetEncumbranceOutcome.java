package com.expense_management_service.dto.response;

import java.util.List;

/** Result of one {@code BudgetEncumbranceService.validateAndEncumber} call - the created encumbrances plus any non-blocking warnings. */
public record BudgetEncumbranceOutcome(
        List<BudgetEncumbranceResponse> encumbrances,
        List<BudgetWarning> warnings
) {
}
