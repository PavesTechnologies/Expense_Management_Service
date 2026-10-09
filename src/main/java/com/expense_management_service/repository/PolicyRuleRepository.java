package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {

    List<PolicyRule> findByCategory_CategoryId(UUID categoryId);

    /** Scopes rule lookup to the employee's one resolved policy - see {@code DefaultPolicyEvaluator}. */
    List<PolicyRule> findByPolicy_PolicyIdAndCategory_CategoryIdAndStatus(UUID policyId, UUID categoryId, String status);

    /** Delete guard for a policy bundle - see {@code PolicyServiceImpl#delete}. */
    boolean existsByPolicy_PolicyId(UUID policyId);
}
