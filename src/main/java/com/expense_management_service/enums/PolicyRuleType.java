package com.expense_management_service.enums;

/**
 * The kind of check a {@link com.expense_management_service.entity.PolicyRule} performs against an
 * {@link com.expense_management_service.entity.ExpenseLineItem}. EP05 is advisory-only — every type
 * here produces a warning, never a block. See {@code PolicyEvaluator} for the evaluation semantics
 * and what {@code ruleValue} means for each type.
 */
public enum PolicyRuleType {
    /** {@code ruleValue} is a {@code BigDecimal} ceiling on the line item's base (or raw) amount. */
    AMOUNT_LIMIT,
    /** Fires when the category requires a receipt and none is attached. {@code ruleValue} is unused. */
    RECEIPT_REQUIRED,
    /** {@code ruleValue} is an integer day count; fires when the expense date is older than that many days. */
    BACKDATED_DAYS,
    /** Fires when the line item's description is blank. {@code ruleValue} is unused. */
    MISSING_DESCRIPTION,
    /** Fires when the same employee has another line item with the same category, date, and amount. {@code ruleValue} is unused. */
    DUPLICATE_EXPENSE
}
