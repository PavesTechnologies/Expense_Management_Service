package com.expense_management_service.enums;

/**
 * Fields an {@code ApprovalFlowCriterion} can evaluate against a submitted report. {@code CATEGORY}
 * lives on each {@code ExpenseLineItem}, not the report itself, so it is matched with "any line item
 * matches" (OR-aggregated across the report's line items at evaluation time, not stored).
 */
public enum CriterionField {
    /** Report total, converted to the configured base currency before comparison. */
    AMOUNT,
    /** Matches if at least one line item on the report belongs to this category. */
    CATEGORY,
    DEPARTMENT,
    COST_CENTER
}
