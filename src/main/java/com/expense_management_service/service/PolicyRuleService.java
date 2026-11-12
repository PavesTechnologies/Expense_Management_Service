package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import java.util.List;
import java.util.UUID;

public interface PolicyRuleService {

    PolicyRuleResponse create(PolicyRuleRequest request);

    PolicyRuleResponse update(UUID policyId, PolicyRuleRequest request);

    PolicyRuleResponse getById(UUID policyId);

    List<PolicyRuleResponse> getAll();

    /** All rules configured for a single category — the shape an admin UI actually needs, rather than the full table. */
    List<PolicyRuleResponse> getAllForCategory(UUID categoryId);

    void delete(UUID policyId);
}
