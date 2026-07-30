package com.expense_management_service.enums;

/**
 * How a single {@code ApprovalMatrix} level resolves: one approver at a time,
 * or several simultaneously with an any/all completion rule.
 */
public enum ApprovalMode {
    SEQUENTIAL,
    PARALLEL_ANY,
    PARALLEL_ALL
}
