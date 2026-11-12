package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApproverExceptionRequest;
import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;

import java.util.List;
import java.util.UUID;

public interface PolicyViolationService {

    List<PolicyWarningResponse> getForLineItem(UUID reportId, UUID lineItemId);

    /** Annotates a violation with an employee explanation — never clears or suppresses the warning itself. */
    PolicyWarningResponse justify(UUID reportId, UUID lineItemId, UUID violationId, PolicyJustificationRequest request);

    /**
     * A SEPARATE, approver-side authorization of an exception — distinct from {@link #justify}, which
     * is the report owner's own note. Callable only by whoever is an active approver (or delegate) for
     * the report's current ACTIVE approval-level instance; rejects if the violation was already
     * authorized once. Never clears or suppresses the underlying warning, same as {@link #justify}.
     */
    PolicyWarningResponse approveException(UUID reportId, UUID lineItemId, UUID violationId, String actingApproverId, ApproverExceptionRequest request);
}
