package com.expense_management_service.service;

import com.expense_management_service.dto.response.EscalationRunResponse;

/**
 * SLA reminder sweep (§5.4, reconfirmed post-Zoho-pivot after full market research - matches 7 of 8
 * studied systems; Concur is the sole outlier with real auto-escalation). Reminders only - a human
 * (the approver or Admin) must set a delegate to actually move a stalled assignment. Runs as SYSTEM -
 * never touches CurrentUserService or UmsClient, neither of which has anything to work with on a
 * scheduler thread (no request-bound SecurityContext).
 */
public interface EscalationService {

    /** Scans every ACTIVE assignment past its due date and fires a reminder event for each. Never reassigns anything. */
    EscalationRunResponse runReminderSweep();
}
