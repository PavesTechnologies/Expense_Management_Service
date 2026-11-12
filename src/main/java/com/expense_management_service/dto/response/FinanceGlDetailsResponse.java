package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The GL accounts a line posts to once Finance verifies it - the same split the tax journal export
 * uses: the category's expense GL takes gross minus recoverable tax, the tax code's input tax GL
 * takes the recoverable tax. Amounts are in the organization base currency ({@code currencyCode}).
 */
public record FinanceGlDetailsResponse(
        UUID glAccountId,
        String glAccountCode,
        String glAccountName,
        String accountType,
        String description,
        /** ACTIVE / INACTIVE - an inactive expense GL blocks verification. */
        String status,
        /** Base gross - base recoverable tax: net plus non-recoverable tax. */
        BigDecimal expenseAmount,
        /** Null when the line has no recoverable tax, or its tax code has no input tax GL mapped. */
        String inputTaxGlAccountCode,
        String inputTaxGlAccountName,
        /** Base recoverable tax; zero when nothing is recoverable. */
        BigDecimal inputTaxAmount,
        String currencyCode
) {
}
