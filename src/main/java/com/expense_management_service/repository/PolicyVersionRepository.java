package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {

    Optional<PolicyVersion> findTopByPolicy_PolicyIdOrderByVersionNumberDesc(UUID policyId);

    List<PolicyVersion> findByPolicy_PolicyIdOrderByVersionNumberDesc(UUID policyId);
}
