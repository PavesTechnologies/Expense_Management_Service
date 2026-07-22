package com.expense_management_service.repository;

import com.expense_management_service.entity.ExpenseCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {

    List<ExpenseCategory> findByGlAccount_GlAccountId(UUID glAccountId);

    long countByGlAccount_GlAccountId(UUID glAccountId);

    List<ExpenseCategory> findByGlAccount_GlAccountIdAndStatusIgnoreCase(UUID glAccountId, String status);
}
