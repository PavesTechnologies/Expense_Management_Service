package com.expense_management_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line item sent back to the employee in the current correction loop - by an approver
 * (Needs Correction) or by Finance (a verification query).
 *
 * @param requestedBy employee ID of whoever sent it back; null when the assigned approver acted
 *                    themselves (only a delegate is recorded on an approver review)
 */
public record CorrectionRequestResponse(
        UUID lineItemId,
        String comment,
        String requestedBy,
        LocalDateTime requestedAt
) {
}
