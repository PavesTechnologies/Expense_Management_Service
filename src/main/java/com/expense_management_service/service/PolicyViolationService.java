package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyJustificationRequest;
import com.expense_management_service.dto.response.PolicyWarningResponse;

import java.util.List;
import java.util.UUID;

public interface PolicyViolationService {

    List<PolicyWarningResponse> getForLineItem(UUID reportId, UUID lineItemId);

    /** Annotates a violation with an employee explanation — never clears or suppresses the warning itself. */
    PolicyWarningResponse justify(UUID reportId, UUID lineItemId, UUID violationId, PolicyJustificationRequest request);
}
