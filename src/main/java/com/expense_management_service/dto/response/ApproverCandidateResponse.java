package com.expense_management_service.dto.response;

import java.util.UUID;

/**
 * An active UMS user offered as a department approver, matched to their employee record (by work
 * email, else by UMS user id) since approvals are assigned by employee ID.
 *
 * @param employeeId        the employee ID an approval is assigned to; null if no employee record
 *                          matches this UMS user
 * @param selectable        whether this person can be saved as an approver
 * @param unavailableReason why not, when {@code selectable} is false
 */
public record ApproverCandidateResponse(
        String employeeId,
        String name,
        String email,
        UUID umsUserUuid,
        String departmentUuid,
        String employmentStatus,
        boolean selectable,
        String unavailableReason
) {
}
