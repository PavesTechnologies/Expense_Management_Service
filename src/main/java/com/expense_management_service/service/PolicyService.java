package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyRequest;
import com.expense_management_service.dto.response.PolicyResponse;
import com.expense_management_service.dto.response.PolicyVersionResponse;

import java.util.List;
import java.util.UUID;

public interface PolicyService {

    PolicyResponse create(PolicyRequest request);

    /** Metadata only (name/description/status) - never bumps the policy's version, since nothing evaluation-relevant changes. */
    PolicyResponse update(UUID policyId, PolicyRequest request);

    PolicyResponse getById(UUID policyId);

    List<PolicyResponse> getAll();

    void delete(UUID policyId);

    List<PolicyVersionResponse> getVersionHistory(UUID policyId);
}
