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
    COST_CENTER,
    /**
     * True if the report has any recorded {@code PolicyViolation} at flow-resolution time (evaluated
     * fresh at every {@code submit()}/resubmission, after {@code PolicyEvaluationGateway.evaluate()}
     * has already run and persisted violations for this cycle). Lets an Admin configure a separate,
     * higher-priority {@code ApprovalFlow} (with its own extra approval level) that only matches a
     * report carrying a policy exception — only EQUALS/NOT_EQUALS operators are valid, same as every
     * other non-AMOUNT field. {@code ApprovalFlowCriterion.value} is ignored for this field: EQUALS
     * means "the report has a violation", NOT_EQUALS means "it has none" — there is nothing else to
     * compare a value against.
     */
    HAS_POLICY_VIOLATION
}
