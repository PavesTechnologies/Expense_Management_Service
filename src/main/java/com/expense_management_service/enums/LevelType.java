package com.expense_management_service.enums;

/**
 * What kind of review an {@code ApprovalLevel} performs. Snapshotted onto {@code
 * ApprovalLevelInstance} at materialization time, same as {@code quorum}/{@code levelOrder} - a
 * config edit after submission never changes an in-flight level's type.
 */
public enum LevelType {
    /** A normal human sign-off level, reviewed via {@code ApprovalLineItemReview}. */
    APPROVAL,
    /** A Finance verification level, reviewed via {@code FinanceVerificationReview} instead. */
    FINANCE_VERIFICATION
}
