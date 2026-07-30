package com.expense_management_service.enums;

/**
 * EP05 is deliberately a 2-tier, advisory-only severity model — there is no blocking tier. A
 * violation of either severity is surfaced to the employee and the approver; neither ever prevents
 * a save or a submission (see {@code PolicyEvaluator}).
 */
public enum PolicySeverity {
    /** A notable compliance concern (e.g. over the category limit, missing a required receipt). */
    WARN,
    /** A lower-signal note (e.g. a suspected duplicate) that may often be a false positive. */
    INFO
}
