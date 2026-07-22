package com.expense_management_service.repository;

import com.expense_management_service.entity.PolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {
}
