package com.expense_management_service.enums;

/**
 * Comparison applied between an {@code ApprovalFlowCriterion.value} and the report's actual value
 * for its {@code field}. {@code GREATER_THAN}/{@code LESS_THAN} variants are only meaningful for
 * {@code CriterionField.AMOUNT}; {@code EQUALS}/{@code NOT_EQUALS} apply to every field.
 */
public enum CriterionOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL
}
