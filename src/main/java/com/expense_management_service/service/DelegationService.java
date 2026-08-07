package com.expense_management_service.service;

import java.util.Optional;

/**
 * Dynamic approval-authority check, carried over from EP06 unchanged and source-agnostic (§5.2/§5.3
 * - applies to whoever resolves as approver, regardless of which of the 4 approver-source types
 * produced them). {@code ApprovalAssignment.approverId} is never rewritten when a delegation starts
 * or ends - this is the single rule that instead governs who may currently act on an assignment,
 * evaluated fresh on every call:
 * <pre>
 * canAct(user, assignment) = user == assignment.approverId
 *                          OR an active delegation exists where delegatorId == assignment.approverId
 *                             AND today BETWEEN startDate AND endDate
 * </pre>
 * This one rule delivers all three tracker behaviours for free: new assignments are actionable by
 * the delegate the moment a delegation starts, already-pending assignments become actionable without
 * any data migration, and authority reverts to the original approver automatically at expiry with no
 * scheduled "revert" job. Also the first link in the self-approval cascade (§5.1).
 */
public interface DelegationService {

    /**
     * @param actingEmployeeId the employeeId attempting to act on the assignment
     * @param approverId       the assignment's approver (ApprovalAssignment.approverId - never changes)
     * @return true if actingEmployeeId is the approver themselves, or is currently that approver's
     *         active delegate (per the overlap rule: if multiple delegations for the same
     *         delegator cover today, the most recently created one wins)
     */
    boolean canAct(String actingEmployeeId, String approverId);

    /**
     * Resolves who approverId's active delegate is today, if any - used by the self-approval
     * cascade (§5.1) and the SLA reminder sweep's display context. Same overlap rule as
     * {@link #canAct}: if multiple delegations cover today, the most recently created one wins.
     */
    Optional<String> resolveActiveDelegate(String approverId);
}
