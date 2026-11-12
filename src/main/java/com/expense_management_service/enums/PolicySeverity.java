package com.expense_management_service.enums;

/**
 * A 2-tier signal-strength model, independent of enforcement. Whether a violation of either tier
 * ever prevents a submission is decided per rule by {@link PolicyEnforcementType}, not by this
 * enum — a rule's severity never changes whether it can block, only how it reads to a human.
 */
public enum PolicySeverity {
    /** A notable compliance concern (e.g. over the category limit, missing a required receipt). */
    WARN,
    /** A lower-signal note (e.g. a suspected duplicate) that may often be a false positive. */
    INFO
}
