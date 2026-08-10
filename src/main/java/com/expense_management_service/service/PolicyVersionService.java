package com.expense_management_service.service;

import com.expense_management_service.dto.response.PolicyVersionResponse;
import com.expense_management_service.entity.Policy;

import java.util.List;
import java.util.UUID;

public interface PolicyVersionService {

    /**
     * Logs that {@code policy}'s rule content just changed and returns the new current version
     * number. Called once per admin write to a policy's rules/limits - see {@code
     * PolicyRuleServiceImpl}'s create/update/delete.
     */
    int activateNewVersion(Policy policy);

    /** 1 for a policy that has never had a logged change - see {@code PolicyVersion}'s own javadoc. */
    int getCurrentVersion(UUID policyId);

    List<PolicyVersionResponse> getVersionHistory(UUID policyId);
}
