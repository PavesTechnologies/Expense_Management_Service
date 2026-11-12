package com.expense_management_service.dto.response;

import java.util.UUID;

/**
 * One department, as Employee Onboarding lists it, with the approver configured for it in XMS
 * (all approver fields null when none is configured yet).
 *
 * @param departmentInOnboarding false for a mapping whose department Employee Onboarding no longer
 *                               returns - kept visible so an Admin can clean it up
 * @param approverActive         whether the approver is still an Active employee; a mapping to
 *                               someone who has left resolves to nobody at approval time
 */
public record DepartmentApproverOverviewResponse(
        UUID departmentUuid,
        String departmentName,
        String departmentDescription,
        boolean departmentInOnboarding,
        UUID departmentApproverId,
        String approverEmployeeId,
        String approverName,
        String approverEmail,
        Boolean approverActive,
        String status
) {
}
