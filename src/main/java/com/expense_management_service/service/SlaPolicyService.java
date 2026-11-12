package com.expense_management_service.service;

/**
 * Shared by {@code ApprovalWorkflowService} (setting a task's initial due date) and
 * {@code EscalationService} (giving an escalated task's replacement a fresh SLA window) -
 * extracted so the SystemConfiguration key and default live in exactly one place.
 */
public interface SlaPolicyService {

    /** Business days an approval task gets before it's considered SLA-breached. Configurable via SystemConfiguration. */
    int resolveSlaBusinessDays();
}
