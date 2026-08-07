package com.expense_management_service.enums;

/**
 * How multiple {@code ApprovalLevelApprover} entries on one {@code ApprovalLevel} interact. This is
 * also how parallel approval happens - it is not a separate mechanism from "a level has several
 * approver-source entries".
 */
public enum LevelQuorum {
    /** Entries act one after another, in {@code entryOrder}. */
    SEQUENTIAL,
    /** Any one resolved approver acting completes the level. */
    ANY_OF,
    /** Every resolved approver must act before the level completes. */
    ALL_OF
}
