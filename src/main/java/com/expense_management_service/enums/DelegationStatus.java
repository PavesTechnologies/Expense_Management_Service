package com.expense_management_service.enums;

/**
 * Lifecycle states for {@code ApprovalDelegation.status}.
 * <p>
 * ACTIVE vs. SCHEDULED/EXPIRED is date-driven (see EP06 plan, Phase 3's
 * dynamic {@code canAct(user, task)} check) rather than transitioned by
 * application code on a timer - only CANCELLED is ever written explicitly.
 */
public enum DelegationStatus {
    SCHEDULED,
    ACTIVE,
    EXPIRED,
    CANCELLED
}
