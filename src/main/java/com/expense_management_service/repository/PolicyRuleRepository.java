package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {

    List<PolicyRule> findByCategory_CategoryId(UUID categoryId);

    List<PolicyRule> findByCategory_CategoryIdAndStatus(UUID categoryId, String status);
}
