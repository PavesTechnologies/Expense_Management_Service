package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicySeverityThresholdRequest;
import com.expense_management_service.dto.response.PolicySeverityThresholdResponse;

import java.util.List;
import java.util.UUID;

public interface PolicySeverityThresholdService {

    /** {@code policyId} null returns the global default band set. */
    List<PolicySeverityThresholdResponse> getForScope(UUID policyId);

    /** Replaces the entire band set for the scope (delete-then-insert) - {@code policyId} null targets the global defaults. */
    List<PolicySeverityThresholdResponse> replaceForScope(UUID policyId, List<PolicySeverityThresholdRequest> requests);
}
