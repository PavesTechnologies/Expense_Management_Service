package com.expense_management_service.repository;

import com.expense_management_service.entity.CostCenterBudget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CostCenterBudgetRepository extends JpaRepository<CostCenterBudget, UUID> {
}
