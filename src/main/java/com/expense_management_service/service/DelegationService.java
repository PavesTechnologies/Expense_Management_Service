package com.expense_management_service.service;

import java.util.Optional;

/**
 * Dynamic approval-authority check (EP06 plan, Phase 3). {@code ApprovalTask.approverId} is never
 * rewritten when a delegation starts or ends - this is the single rule that instead governs who
 * may currently act on a task, evaluated fresh on every call:
 * <pre>
 * canAct(user, task) = user == task.approverId
 *                    OR an active delegation exists where delegatorId == task.approverId
 *                       AND today BETWEEN startDate AND endDate
 * </pre>
 * This one rule delivers all three tracker behaviours for free: new tasks are actionable by the
 * delegate the moment a delegation starts, already-pending tasks become actionable without any
 * data migration, and authority reverts to the original approver automatically at expiry with no
 * scheduled "revert" job.
 */
public interface DelegationService {

    /**
     * @param actingEmployeeId the employeeId attempting to act on the task
     * @param approverId       the task's assigned approver (ApprovalTask.approverId - never changes)
     * @return true if actingEmployeeId is the approver themselves, or is currently that approver's
     *         active delegate (per the overlap rule: if multiple delegations for the same
     *         delegator cover today, the most recently created one wins)
     */
    boolean canAct(String actingEmployeeId, String approverId);

    /**
     * Resolves who approverId's active delegate is today, if any - used by
     * {@code EscalationService} to pick an escalation target. Same overlap rule as
     * {@link #canAct}: if multiple delegations cover today, the most recently created one wins.
     */
    Optional<String> resolveActiveDelegate(String approverId);
}
