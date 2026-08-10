package com.expense_management_service.enums;

/**
 * Lifecycle of one resolved, snapshotted {@code ApprovalLevelInstance} on a specific report and
 * submission cycle. Mirrors the "materialise every level upfront, activate progressively" design
 * carried over from EP06.
 */
public enum LevelInstanceStatus {
    /** Materialised as part of the resolved chain, but not yet this level's turn. */
    QUEUED,
    /** This level is the current one - its assignments are being worked. */
    ACTIVE,
    /** Every line item at this level reached APPROVED; the chain has moved past it. */
    COMPLETED,
    /** Superseded by a restart (a different flow now matches) or the report left the chain (recall/cancel/reject). */
    CANCELLED
}
