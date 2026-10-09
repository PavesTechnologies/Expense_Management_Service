package com.expense_management_service.repository;

import com.expense_management_service.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    /** Used to resolve the fallback bundle when a {@code PolicyRule} is created without an explicit policyBundleId. */
    Optional<Policy> findByPolicyName(String policyName);
}
