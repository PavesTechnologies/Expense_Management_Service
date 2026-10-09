package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyGroupRepository extends JpaRepository<PolicyGroup, UUID> {

    Optional<PolicyGroup> findByGroupName(String groupName);
}
