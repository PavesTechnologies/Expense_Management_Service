package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicySeverityThreshold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicySeverityThresholdRepository extends JpaRepository<PolicySeverityThreshold, UUID> {

    List<PolicySeverityThreshold> findByPolicy_PolicyIdOrderByMinPercentOverAsc(UUID policyId);

    List<PolicySeverityThreshold> findByPolicyIsNullOrderByMinPercentOverAsc();

    void deleteByPolicy_PolicyId(UUID policyId);

    void deleteByPolicyIsNull();
}
