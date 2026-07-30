package com.expense_management_service.service;

import com.expense_management_service.dto.response.EscalationRunResponse;

/**
 * Prevents reports from stalling indefinitely due to approver inaction (EP06 plan, Phase 4 / BR-12).
 * Runs as SYSTEM - never touches CurrentUserService or UmsClient, neither of which has anything
 * to work with on a scheduler thread (no request-bound SecurityContext).
 */
public interface EscalationService {

    /**
     * Scans every PENDING task past its due date. For each: reassigns to the approver's active
     * delegate if one exists, else to the approver's own manager (a "skip-level" escalation), by
     * marking the overdue task ESCALATED and creating a fresh PENDING replacement with its own
     * SLA window. If neither target exists, the task is left exactly as it is and a warning is
     * logged - it is never silently dropped or corrupted.
     */
    EscalationRunResponse runEscalationSweep();
}
