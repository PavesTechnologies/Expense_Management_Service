package com.expense_management_service.service;

import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PolicyRuleService {

    PolicyRuleResponse create(PolicyRuleRequest request);

    PolicyRuleResponse update(UUID policyId, PolicyRuleRequest request);

    PolicyRuleResponse getById(UUID policyId);

    Page<PolicyRuleResponse> getAll(Pageable pageable);

    void delete(UUID policyId);
}
