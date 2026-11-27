package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

/**
 * One recorded decision in a cash advance's approval chain, read from the audit log.
 *
 * @param action      SUBMITTED, APPROVED, REJECTED or DEFERRED
 * @param fromStatus  the advance's status when the decision was taken (tells which level decided)
 * @param toStatus    the status it moved to (e.g. PENDING_FINANCE_APPROVAL straight after the manager = cost center skipped)
 * @param performedBy employee id of whoever acted (the approver or their delegate)
 * @param performedAt when
 * @param reason      rejection or deferral reason, when there is one
 */
public record CashAdvanceApprovalStepResponse(
        String action,
        String fromStatus,
        String toStatus,
        String performedBy,
        LocalDateTime performedAt,
        String reason
) {
}
