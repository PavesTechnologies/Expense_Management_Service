package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyRuleLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyRuleLimitRepository extends JpaRepository<PolicyRuleLimit, UUID> {

    /** Empty means the rule is in legacy flat-limit mode - see {@link PolicyRuleLimit}'s own javadoc. */
    List<PolicyRuleLimit> findByPolicyRule_PolicyId(UUID policyRuleId);
}
