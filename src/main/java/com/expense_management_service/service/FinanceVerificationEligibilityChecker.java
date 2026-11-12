package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseLineItem;

/**
 * Decides whether a line item is currently eligible for Finance VERIFY - receipt, then policy,
 * then GL account, in that order, stopping at the first failure. Called both by {@code
 * FinanceVerificationServiceImpl.verifyLineItem} (server-authoritative gate) and by the "My Queue"
 * read model (to show Finance why a line is not yet actionable) - the same checker backs both, so
 * they can never drift apart.
 */
public interface FinanceVerificationEligibilityChecker {

    FinanceEligibilityResult check(ExpenseLineItem lineItem);
}
