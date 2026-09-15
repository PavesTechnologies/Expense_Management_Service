package com.expense_management_service.enums;

/**
 * How an {@code ExpenseSplit}'s {@code allocatedAmount} was derived. Snapshotted at creation - a
 * split's mode never changes after the fact; correcting it means deleting and recreating the split.
 */
public enum SplitType {
    /** {@code percentage} drives {@code allocatedAmount}; the line item's splits must sum to 100%. */
    PERCENTAGE,
    /** {@code allocatedAmount} is entered directly; {@code percentage} is not populated. */
    FIXED_AMOUNT
}
